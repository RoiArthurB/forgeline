package fr.arthurbrugiere.forgeline.core.forge

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ContactLink
import fr.arthurbrugiere.forgeline.core.model.IssueChooser
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import org.junit.Test

class IssueFormsTest {
    /** Cut down from facebook/react's `.github/ISSUE_TEMPLATE/compiler_bug_report.yml`, read on 2026-10-25. */
    private val compilerForm = """
        name: "⚛️ ✨ Compiler bug report"
        description: "Report a problem with React Compiler."
        title: "[Compiler Bug]: "
        labels: ["Component: Optimizing Compiler", "Type: Bug", "Status: Unconfirmed"]
        body:
        - type: markdown
          attributes:
            value: Thanks for taking the time to fill out this form.
        - type: checkboxes
          attributes:
            label: What kind of issue is this?
            description: |
              Please indicate if this issue affects the following tools.
            options:
              - label: React Compiler core
              - label: babel-plugin-react-compiler
        - type: input
          attributes:
            label: Link to repro
            placeholder: |
              e.g. public GitHub repo, or Playground link
          validations:
            required: true
        - type: dropdown
          attributes:
            label: How often does this bug happen?
            options:
              - Every time
              - Only once
        - type: textarea
          attributes:
            label: Anything else?
            value: |
              Nothing yet.
    """.trimIndent()

    @Test
    fun a_github_issue_form_is_a_template_whose_body_is_what_the_form_writes() {
        assertThat(parseIssueForm("compiler_bug_report.yml", compilerForm)).isEqualTo(
            IssueTemplate(
                key = "compiler_bug_report.yml",
                name = "⚛️ ✨ Compiler bug report",
                about = "Report a problem with React Compiler.",
                title = "[Compiler Bug]: ",
                // The form's own words stay beside the form; a placeholder only shows in an empty field.
                body = """
                    ### What kind of issue is this?

                    <!-- Please indicate if this issue affects the following tools. -->
                    - [ ] React Compiler core
                    - [ ] babel-plugin-react-compiler

                    ### Link to repro


                    ### How often does this bug happen?

                    - Every time
                    - Only once

                    ### Anything else?

                    Nothing yet.

                """.trimIndent(),
                labels = listOf("Component: Optimizing Compiler", "Type: Bug", "Status: Unconfirmed"),
            ),
        )
    }

    @Test
    fun labels_written_on_one_line_are_split_by_commas() {
        val form = parseIssueForm("bug.yaml", "name: Bug\nlabels: bug, needs triage ,\nbody:\n- type: input\n  attributes:\n    label: Version\n")

        assertThat(form?.labels).containsExactly("bug", "needs triage").inOrder()
        assertThat(form?.title).isEmpty()
        assertThat(form?.about).isNull()
    }

    @Test
    fun what_is_no_form_gives_no_template() {
        // GitHub's config.yml, a form without a name or fields, and YAML that doesn't read.
        assertThat(parseIssueForm("config.yml", "blank_issues_enabled: false\ncontact_links: []\n")).isNull()
        assertThat(parseIssueForm("a.yml", "name: Nothing to fill\nbody: []\n")).isNull()
        assertThat(parseIssueForm("b.yml", "body:\n- type: input\n  attributes:\n    label: Version\n")).isNull()
        assertThat(parseIssueForm("c.yml", "name: [unclosed\n")).isNull()
        assertThat(parseIssueForm("d.yml", "- just\n- a list\n")).isNull()
    }

    @Test
    fun a_form_s_own_words_meant_for_the_issue_are_kept() {
        val text = issueFormText(listOf(FormField("markdown", value = "Filed from the app.\n", inIssue = true), FormField("input", label = "Version", value = "1.0")))

        assertThat(text).isEqualTo("Filed from the app.\n\n### Version\n\n1.0\n")
    }

    @Test
    fun a_config_says_whether_issues_may_start_blank_and_where_else_to_go() {
        // facebook/react's `.github/ISSUE_TEMPLATE/config.yml`, read on 2026-10-25.
        val config = """
            blank_issues_enabled: false
            contact_links:
                - name: 📃 Documentation Issue
                  url: https://github.com/reactjs/react.dev/issues/new/choose
                  about: This issue tracker is not for documentation issues. Please file documentation issues here.
                - name: 🤔 Questions and Help
                  url: https://reactjs.org/community/support.html
                  about: This issue tracker is not for support questions. Please refer to the React community's help and discussion forums.
        """.trimIndent()

        val chooser = parseIssueChooser(config)

        assertThat(chooser.blankIssues).isFalse()
        assertThat(chooser.links.map { it.name to it.url }).containsExactly(
            "📃 Documentation Issue" to "https://github.com/reactjs/react.dev/issues/new/choose",
            "🤔 Questions and Help" to "https://reactjs.org/community/support.html",
        ).inOrder()
        assertThat(chooser.links[0].about).startsWith("This issue tracker is not for documentation issues.")
    }

    @Test
    fun a_config_that_says_nothing_allows_blank_issues_and_a_link_without_an_address_is_left_out() {
        assertThat(parseIssueChooser("contact_links:\n  - name: Chat\n  - name: Forum\n    url: https://forum.example\n")).isEqualTo(
            IssueChooser(blankIssues = true, links = listOf(ContactLink("Forum", "https://forum.example"))),
        )
        assertThat(parseIssueChooser("blank_issues_enabled: [nonsense\n")).isEqualTo(IssueChooser())
        assertThat(parseIssueChooser("")).isEqualTo(IssueChooser())
    }
}
