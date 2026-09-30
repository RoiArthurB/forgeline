package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Finds the `workflow_dispatch` trigger in a workflow file and the inputs it declares.
 * YAML 1.2 (snakeyaml-engine) matters: YAML 1.1 reads the `on:` key as the boolean true.
 */
object WorkflowDispatchParser {

    /** The inputs, or null when the workflow can't be started by hand (or doesn't parse). */
    fun inputs(yaml: String): List<DispatchInput>? {
        val document = runCatching { Load(LoadSettings.builder().build()).loadFromString(yaml) }.getOrNull() as? Map<*, *> ?: return null
        val trigger = when (val on = document["on"]) {
            is String -> return if (on == DISPATCH) emptyList() else null
            is List<*> -> return if (DISPATCH in on) emptyList() else null
            is Map<*, *> -> if (on.containsKey(DISPATCH)) on[DISPATCH] else return null
            else -> return null
        }
        val inputs = (trigger as? Map<*, *>)?.get("inputs") as? Map<*, *> ?: return emptyList()
        return inputs.mapNotNull { (name, spec) ->
            val input = spec as? Map<*, *> ?: emptyMap<Any, Any>()
            val type = when (input["type"]) {
                "choice" -> DispatchInputType.CHOICE
                "boolean" -> DispatchInputType.BOOLEAN
                "number" -> DispatchInputType.NUMBER
                "environment" -> DispatchInputType.ENVIRONMENT
                else -> DispatchInputType.STRING
            }
            DispatchInput(
                name = name?.toString() ?: return@mapNotNull null,
                description = input["description"]?.toString(),
                type = type,
                required = input["required"] == true,
                default = input["default"]?.toString(),
                options = (input["options"] as? List<*>)?.map { it.toString() }.orEmpty(),
            )
        }
    }

    private const val DISPATCH = "workflow_dispatch"
}
