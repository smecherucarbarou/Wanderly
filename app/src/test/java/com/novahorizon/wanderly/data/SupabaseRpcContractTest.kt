package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseRpcContractTest {

    @Test
    fun `all app called supabase rpcs are defined in repo sql`() {
        val root = projectRoot()
        val appCalledRpcs = root.resolve("app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> rpcCallRegex.findAll(file.readText()).map { match -> match.groupValues[1] } }
            .toSortedSet()

        assertTrue("Expected app source to call at least one Supabase RPC.", appCalledRpcs.isNotEmpty())

        val repoSql = sequenceOf(root.resolve("supabase"))
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "sql" }
            .joinToString(separator = "\n") { it.readText() }

        val missingRpcs = appCalledRpcs.filterNot { rpcName ->
            repoSql.contains(Regex("""CREATE\s+(OR\s+REPLACE\s+)?FUNCTION\s+public\.${Regex.escape(rpcName)}\s*\(""", RegexOption.IGNORE_CASE))
        }

        assertTrue(
            "Missing SQL definitions for app-called Supabase RPCs: ${missingRpcs.joinToString()}",
            missingRpcs.isEmpty()
        )
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }

    private companion object {
        private val rpcCallRegex = Regex("""\.rpc\("([^"]+)"""")
    }
}
