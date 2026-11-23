package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.ProfileQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.UpdateProfileMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.type.UserInput
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** meta.sr.ht's GraphQL endpoint: accounts and their profiles. */
internal const val META_SRHT_QUERY = "https://meta.sr.ht/query"

/**
 * A SourceHut profile, on meta.sr.ht (`updateUser`): it wants a token with meta.sr.ht's `PROFILE` scope, written to
 * (read only, it is refused). A new email address takes effect once confirmed from the email SourceHut sends to it.
 * SourceHut has no organisations.
 */
class SourceHutOwnerSettingsApi(private val httpClient: HttpClient) : OwnerSettingsApi {
    override val profileSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        httpClient.srht(token, ProfileQuery(), META_SRHT_QUERY).map { it.me.run { settings(bio, pronouns, location, url, email) } }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me || change !is OwnerSettingsChange.Text) return@forgeCall unsupported
        // Emptied is cleared: null, as meta.sr.ht asks; the email is never cleared.
        val value = Optional.present(change.value.trim().ifEmpty { null })
        val input = when (change.field) {
            OwnerText.BIO -> UserInput(bio = value)
            OwnerText.PRONOUNS -> UserInput(pronouns = value)
            OwnerText.LOCATION -> UserInput(location = value)
            OwnerText.WEBSITE -> UserInput(url = value)
            OwnerText.EMAIL -> UserInput(email = Optional.present(change.value.trim().ifEmpty { return@forgeCall unsupported }))
            else -> return@forgeCall unsupported
        }
        httpClient.srht(token, UpdateProfileMutation(Optional.present(input)), META_SRHT_QUERY)
            .map { it.updateUser.run { settings(bio, pronouns, location, url, email) } }
    }

    override fun avatarSupported(owner: Owner) = owner == Owner.Me

    override fun avatarRemovable(owner: Owner) = owner == Owner.Me

    /**
     * `updateUser` with the picture as an `Upload`: a GraphQL multipart request (its `operations`, a `map` naming the
     * variable, and the file), as meta.sr.ht takes files.
     */
    override suspend fun setAvatar(token: String, owner: Owner, image: ByteArray, mimeType: String): ForgeResult<Unit> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        val operations = buildJsonObject {
            put("query", "mutation(${'$'}input: UserInput) { updateUser(input: ${'$'}input) { username } }")
            put("variables", buildJsonObject { put("input", buildJsonObject { put("avatar", JsonNull) }) })
        }
        val response = httpClient.post(META_SRHT_QUERY) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/json")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("operations", operations.toString())
                        append("map", """{"0":["variables.input.avatar"]}""")
                        append(
                            "0", image,
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.${mimeType.substringAfter('/')}\"")
                            },
                        )
                    },
                ),
            )
        }
        val answer = runCatching { ForgeJson.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
        val error = ((answer?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
        when {
            response.status == HttpStatusCode.Unauthorized -> ForgeResult.Failure(ForgeError.Unauthorized)
            error != null -> ForgeResult.Failure(ForgeError.Http(if (response.status.isSuccess()) HttpStatusCode.BadRequest.value else response.status.value, error))
            !response.status.isSuccess() -> ForgeResult.Failure(ForgeError.Http(response.status.value, null))
            else -> ForgeResult.Success(Unit)
        }
    }

    override suspend fun removeAvatar(token: String, owner: Owner): ForgeResult<Unit> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        httpClient.srht(token, UpdateProfileMutation(Optional.present(UserInput(avatar = Optional.present(null)))), META_SRHT_QUERY).map { }
    }

    private fun settings(bio: String?, pronouns: String?, location: String?, url: String?, email: String) = OwnerSettings(
        texts = linkedMapOf(
            OwnerText.BIO to bio.orEmpty(),
            OwnerText.PRONOUNS to pronouns.orEmpty(),
            OwnerText.LOCATION to location.orEmpty(),
            OwnerText.WEBSITE to url.orEmpty(),
            OwnerText.EMAIL to email,
        ),
    )

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)
    }
}
