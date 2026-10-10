package fr.arthurbrugiere.forgeline.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChangesTest {
    private val patch = """
        @@ -1,4 +1,5 @@ fun main() {
         val a = 1
        -val b = 2
        +val b = 3
        +val c = 4
         print(a)
        @@ -20,2 +21,2 @@
        -old
        +new
         
        \ No newline at end of file
    """.trimIndent()

    @Test
    fun a_patch_is_read_as_hunks_of_numbered_lines() {
        val hunks = parsePatch(patch)

        assertThat(hunks.map { it.header }).containsExactly("@@ -1,4 +1,5 @@ fun main() {", "@@ -20,2 +21,2 @@").inOrder()
        assertThat(hunks[0].lines).containsExactly(
            DiffLine(DiffLineKind.CONTEXT, "val a = 1", 1, 1),
            DiffLine(DiffLineKind.REMOVED, "val b = 2", 2, null),
            DiffLine(DiffLineKind.ADDED, "val b = 3", null, 2),
            DiffLine(DiffLineKind.ADDED, "val c = 4", null, 3),
            DiffLine(DiffLineKind.CONTEXT, "print(a)", 3, 4),
        ).inOrder()
        // Numbers start again from each hunk's header, and git's own remark is not a line of the file.
        assertThat(hunks[1].lines).containsExactly(
            DiffLine(DiffLineKind.REMOVED, "old", 20, null),
            DiffLine(DiffLineKind.ADDED, "new", null, 21),
            DiffLine(DiffLineKind.CONTEXT, "", 21, 22),
        ).inOrder()
    }

    @Test
    fun the_line_break_that_ends_a_patch_is_not_one_more_line() {
        val hunks = parsePatch("@@ -1 +1 @@\n-a\n+b\n")

        assertThat(hunks.single().lines.map { it.text }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun a_line_that_starts_with_two_signs_is_still_one_change() {
        // "--x" in the file, removed, reads "---x": not a file header, which a patch from its first @@ never holds.
        val hunks = parsePatch("@@ -1,2 +1,2 @@\n---x\n+++y\n ctx")

        assertThat(hunks.single().lines.map { it.kind to it.text })
            .containsExactly(DiffLineKind.REMOVED to "--x", DiffLineKind.ADDED to "++y", DiffLineKind.CONTEXT to "ctx").inOrder()
    }

    @Test
    fun changes_are_counted_from_the_patch() {
        assertThat(countChanges(patch)).isEqualTo(3 to 2)
        assertThat(countChanges("")).isEqualTo(0 to 0)
    }

    private val diff = """
        diff --git a/src/Main.kt b/src/Main.kt
        index 83db48f..bf2a1c3 100644
        --- a/src/Main.kt
        +++ b/src/Main.kt
        @@ -1,2 +1,2 @@
        -old
        +new
         same
        diff --git a/docs/new.md b/docs/new.md
        new file mode 100644
        index 0000000..e69de29
        --- /dev/null
        +++ b/docs/new.md
        @@ -0,0 +1,2 @@
        +# Title
        +Text
        diff --git a/gone.txt b/gone.txt
        deleted file mode 100644
        index e69de29..0000000
        --- a/gone.txt
        +++ /dev/null
        @@ -1 +0,0 @@
        -bye
        diff --git a/old name.txt b/new name.txt
        similarity index 100%
        rename from old name.txt
        rename to new name.txt
        diff --git a/logo.png b/logo.png
        index 1111111..2222222 100644
        Binary files a/logo.png and b/logo.png differ
    """.trimIndent() + "\n"

    @Test
    fun a_whole_diff_is_read_file_by_file() {
        val files = parseUnifiedDiff(diff)

        assertThat(files.map { it.path }).containsExactly("src/Main.kt", "docs/new.md", "gone.txt", "new name.txt", "logo.png").inOrder()
        assertThat(files.map { it.change })
            .containsExactly(FileChange.MODIFIED, FileChange.ADDED, FileChange.REMOVED, FileChange.RENAMED, FileChange.MODIFIED).inOrder()
        assertThat(files.map { it.additions to it.deletions }).containsExactly(1 to 1, 2 to 0, 0 to 1, 0 to 0, 0 to 0).inOrder()
        // A file's own headers are not part of its patch: it starts at the first hunk.
        assertThat(files[0].patch).isEqualTo("@@ -1,2 +1,2 @@\n-old\n+new\n same\n")
        assertThat(parsePatch(files[0].patch!!).single().lines).hasSize(3)
        // A move says where from; a file that isn't text has nothing to read.
        assertThat(files[3].previousPath).isEqualTo("old name.txt")
        assertThat(files[3].patch).isNull()
        assertThat(files[4].patch).isNull()
        assertThat(files[0].previousPath).isNull()
    }

    @Test
    fun what_comes_before_the_first_file_of_a_diff_is_skipped() {
        // A commit's ".patch" opens with the mail headers and the message.
        val files = parseUnifiedDiff("From 1234 Mon Sep 17\nSubject: [PATCH] Fix\n\n---\n a.txt | 2 +-\n\n" + diff)

        assertThat(files).hasSize(5)
    }

    @Test
    fun a_commit_message_gives_its_title_and_the_rest() {
        val commit = Commit("0123456789abcdef", "Fix the upload\n\nIt failed on slow links.\n", "Octo", null, null)

        assertThat(commit.title).isEqualTo("Fix the upload")
        assertThat(commit.description).isEqualTo("It failed on slow links.")
        assertThat(commit.shortSha).isEqualTo("0123456")
        assertThat(Commit("a", "One line", null, null, null).description).isNull()
    }

    @Test
    fun checks_as_a_whole_fail_if_one_did_and_run_while_one_does() {
        fun check(state: CheckState) = Check("c", state, null, null)

        assertThat(emptyList<Check>().overall()).isNull()
        assertThat(listOf(check(CheckState.SUCCESS), check(CheckState.SKIPPED)).overall()).isEqualTo(CheckState.SUCCESS)
        assertThat(listOf(check(CheckState.SUCCESS), check(CheckState.PENDING)).overall()).isEqualTo(CheckState.PENDING)
        assertThat(listOf(check(CheckState.PENDING), check(CheckState.FAILURE)).overall()).isEqualTo(CheckState.FAILURE)
        assertThat(listOf(check(CheckState.SKIPPED)).overall()).isEqualTo(CheckState.SKIPPED)
    }
}
