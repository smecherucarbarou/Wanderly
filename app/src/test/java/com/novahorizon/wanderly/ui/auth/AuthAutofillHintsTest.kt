package com.novahorizon.wanderly.ui.auth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAutofillHintsTest {

    @Test
    fun `login fields declare autofill hints`() {
        val xml = projectFile("app/src/main/res/layout/fragment_login.xml").readText()

        assertFieldHasAutofillHint(xml, "email_input", "emailAddress")
        assertFieldHasAutofillHint(xml, "password_input", "password")
    }

    @Test
    fun `signup fields declare autofill hints`() {
        val xml = projectFile("app/src/main/res/layout/fragment_signup.xml").readText()

        assertFieldHasAutofillHint(xml, "email_input", "emailAddress")
        assertFieldHasAutofillHint(xml, "username_input", "username")
        assertFieldHasAutofillHint(xml, "password_input", "password")
        assertFieldHasAutofillHint(xml, "confirm_password_input", "password")
    }

    private fun assertFieldHasAutofillHint(xml: String, fieldId: String, hint: String) {
        val block = Regex("""android:id="@\+id/$fieldId"[\s\S]*?/>""")
            .find(xml)
            ?.value
            ?: error("Field $fieldId not found")
        assertTrue("$fieldId missing autofill hint $hint", block.contains("""android:autofillHints="$hint""""))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        var current: File? = File(userDir).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Project root not found")
    }
}
