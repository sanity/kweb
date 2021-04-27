package kweb.util

import com.google.gson.Gson
import io.mola.galimatias.URL
import kotlinx.serialization.json.*
import org.apache.commons.lang3.StringEscapeUtils
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Created by ian on 1/7/17.
 */


val random = Random()

fun createNonce(length: Int = 6): String {
    val ar = ByteArray(size = length * 2)
    random.nextBytes(ar)
    return Base64.getUrlEncoder().encodeToString(ar).substring(0, length)
}

val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(5)

fun String.escapeEcma() = StringEscapeUtils.escapeEcmaScript(this)!!

val gson = Gson()

data class JsFunction(val jsId: Int, val arguments: List<JsonElement> = emptyList())

fun primitiveToJson(value: Any?, errorMsg: String = "Argument is required to be String or primitive type"): JsonElement {
    return when(value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Byte -> JsonPrimitive(value)
        is JsonElement -> value
        value == null -> JsonNull
        else -> error(errorMsg)
    }
}

fun hashMapToJson(hashMap: HashMap<*, *>) : JsonElement {
    val jsonHashMap = HashMap<String, JsonElement>()
    hashMap.forEach {
        jsonHashMap[it.key.toString()] = primitiveToJson(it.value,
                "You may only put a String or primitive type into a hashmap used as a JS function argument")
    }
    return kotlinx.serialization.json.JsonObject(jsonHashMap)
}

fun <T> warnIfBlocking(maxTimeMs: Long, onBlock: (Thread) -> Unit, f: () -> T): T {
    val runningThread = Thread.currentThread()
    val watcher = scheduledExecutorService.schedule({ onBlock(runningThread) }, maxTimeMs, TimeUnit.MILLISECONDS)
    val r = f()
    watcher.cancel(false)
    return r
}

/**
 * Dump a stacktrace generated by a user-supplied lambda, but attempt to remove irrelevant lines to the
 * trace.  This is a little ugly but seems to work well, there may be a better approach.
 */
fun Array<StackTraceElement>.pruneAndDumpStackTo(logStatementBuilder: StringBuilder) {
    val disregardClassPrefixes = listOf("org.jetbrains.ktor", "io.netty", "java.lang", "kotlin", "kotlinx")
    this.filter { ste -> ste.lineNumber >= 0 && !disregardClassPrefixes.any { ste.className.startsWith(it) } }.forEach { stackTraceElement ->
        logStatementBuilder.appendln("        at ${stackTraceElement.className}.${stackTraceElement.methodName}(${stackTraceElement.fileName}:${stackTraceElement.lineNumber})")
    }
}

val <T : Any> KClass<T>.pkg: String
    get() {
        val packageName = qualifiedName
        val className = simpleName
        return if (packageName != null && className != null) {
            val endIndex = packageName.length - className.length - 1
            packageName.substring(0, endIndex)
        } else {
            error("Cannot determine package for $this because it may be local or an anonymous object literal")
        }
    }

data class NotFoundException(override val message: String) : Exception(message)

val URL.pathQueryFragment: String
    get() {
        val sb = StringBuilder()
        if (path() != null) {
            sb.append(path())
        }
        if (query() != null) {
            sb.append('?').append(query())
        }
        if (fragment() != null) {
            sb.append('#').append(fragment())
        }
        return sb.toString()
    }

@DslMarker
annotation class KWebDSL