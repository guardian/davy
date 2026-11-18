package com.gu.davy

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI
import java.util.regex.PatternSyntaxException

/** A value read from JSON or YAML. */
internal sealed class Json {
    data object Null : Json() {
        override fun toString() = "null"
    }

    data class Bool(val value: Boolean) : Json() {
        override fun toString() = value.toString()
    }

    data class Num(val value: Double) : Json() {
        override fun toString() = if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    }

    data class Str(val value: String) : Json() {
        override fun toString() = "\"$value\""
    }

    data class Arr(val items: List<Json>) : Json() {
        override fun toString() = items.joinToString(", ", "[", "]")
    }

    data class Obj(val fields: Map<String, Json>) : Json() {
        override fun toString() = fields.toSortedMap().entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" }
    }

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)

    val string: String? get() = (this as? Str)?.value
    val number: Double? get() = (this as? Num)?.value
    val array: List<Json>? get() = (this as? Arr)?.items
    val obj: Map<String, Json>? get() = (this as? Obj)?.fields

    fun isOfType(type: String): Boolean = when (type) {
        "null" -> this is Null
        "boolean" -> this is Bool
        "number" -> this is Num
        "integer" -> this is Num && this.value % 1.0 == 0.0
        "string" -> this is Str
        "array" -> this is Arr
        "object" -> this is Obj
        else -> false
    }

    companion object {
        /** Converts the output of org.json or SnakeYAML. */
        fun of(value: Any?): Json = when (value) {
            null, JSONObject.NULL -> Null
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Number -> Num(value.toDouble())
            is List<*> -> Arr(value.map(::of))
            is JSONArray -> Arr((0 until value.length()).map { of(value.get(it)) })
            is Map<*, *> -> Obj(value.entries.associate { (key, item) -> key.toString() to of(item) })
            is JSONObject -> Obj(value.keys().asSequence().associateWith { of(value.get(it)) })
            else -> throw IllegalArgumentException("Unsupported value of type ${value::class.java.name}")
        }

        /** Parses [json], or returns `null` if it isn't valid JSON. */
        fun parse(json: String): Json? = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) null else of(value)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * `Spec/openapi.yaml`, with just enough of OpenAPI 3.1 and JSON Schema to check requests against
 * it. A port of the Swift tests' `OpenAPISpec`, so both clients are checked the same way.
 *
 * Anything in the spec this doesn't understand, such as a new schema keyword, is reported as a
 * violation rather than skipped, so no part of the spec silently goes unchecked.
 */
internal class OpenApiSpec(private val document: Json) {
    companion object {
        /** `Spec`, holding the spec and the shared fixtures. */
        val directory = File(
            System.getProperty("davy.specDirectory") ?: error("Run the tests with Gradle, which sets davy.specDirectory"),
        )

        fun load(): OpenApiSpec = OpenApiSpec(Json.of(Yaml().load<Any>(File(directory, "openapi.yaml").readText())))

        /**
         * Keywords that only describe a schema. In JSON Schema 2020-12, `format` is an annotation
         * too, so the spec uses `pattern` for formats that must be checked.
         */
        private val annotations = setOf("description", "examples", "title", "format", "default", "deprecated", "\$comment")
        private val methods = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
    }

    /** Every operation, named like `"POST /events"`. */
    val operations: List<Pair<String, Json>>
        get() = document["paths"]?.obj.orEmpty().toSortedMap().flatMap { (path, item) ->
            methods.mapNotNull { method -> item[method]?.let { "${method.uppercase()} $path" to it } }
        }

    /**
     * The name of the response component [operation] uses for [statusCode], such as `"Rejected"`,
     * preferring an exact code, then a range such as `2XX`, then `default`, as OpenAPI does.
     */
    fun responseName(statusCode: Int, operation: Json): String? {
        val responses = operation["responses"]
        val response = responses?.get(statusCode.toString())
            ?: responses?.get("${statusCode / 100}XX")
            ?: responses?.get("default")
        return response?.get("\$ref")?.string?.substringAfterLast('/')
    }

    /** Every way [request] breaks the spec, or an empty list if it conforms. */
    fun violations(request: HttpRequest): List<String> {
        val url = try {
            URI(request.url)
        } catch (_: Exception) {
            return listOf("The URL isn't valid: ${request.url}")
        }
        val server = document["servers"]?.array?.firstOrNull()?.get("url")?.string?.let(::URI)
        val serverPath = server?.rawPath.orEmpty()
        val urlPath = url.rawPath.orEmpty()
        if (server == null || url.scheme != server.scheme || url.host != server.host || url.port != server.port || !urlPath.startsWith(serverPath)) {
            return listOf("The URL isn't on the spec's server")
        }
        val path = urlPath.removePrefix(serverPath)
        val pathItem = document["paths"]?.get(path) ?: return listOf("No path in the spec matches $path")
        val operation = pathItem["post"] ?: return listOf("$path has no POST operation")
        val queryViolations = if (url.rawQuery.isNullOrEmpty()) emptyList() else listOf("The spec defines no query parameters for $path")
        return queryViolations + parameterViolations(request, operation) + bodyViolations(request, operation)
    }

    private fun parameterViolations(request: HttpRequest, operation: Json): List<String> {
        val violations = mutableListOf<String>()
        for (entry in operation["parameters"]?.array.orEmpty()) {
            val parameter = resolve(entry)
            val name = parameter?.get("name")?.string
            if (parameter?.get("in") != Json.Str("header") || name == null) {
                violations += "The spec uses a parameter OpenApiSpec doesn't support: $entry"
                continue
            }
            val value = request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            if (value == null) {
                if (parameter["required"] == Json.Bool(true)) {
                    violations += "The request is missing the required header $name"
                }
                continue
            }
            parameter["schema"]?.let {
                violations += schemaViolations(Json.Str(value), it, "header $name")
            }
        }
        return violations
    }

    private fun bodyViolations(request: HttpRequest, operation: Json): List<String> {
        val body = request.body
        val reference = operation["requestBody"]
            ?: return if (body.isEmpty()) emptyList() else listOf("The request has a body, but the spec doesn't define one")
        val requestBody = resolve(reference) ?: return listOf("The spec's requestBody can't be resolved")
        if (body.isEmpty()) {
            return if (requestBody["required"] == Json.Bool(true)) listOf("The request has no body, but the spec requires one") else emptyList()
        }
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.substringBefore(';')?.trim().orEmpty()
        val media = requestBody["content"]?.get(contentType) ?: return listOf("The Content-Type \"$contentType\" isn't in the spec")
        if (contentType != "application/json") return listOf("Only JSON bodies are supported by OpenApiSpec")
        val json = Json.parse(body.decodeToString()) ?: return listOf("The body isn't valid JSON")
        return media["schema"]?.let { schemaViolations(json, it, "body") }.orEmpty()
    }

    /** [value] itself, or the value its `$ref`, such as `#/components/schemas/Event`, points to. */
    private fun resolve(value: Json): Json? {
        val reference = value["\$ref"]?.string ?: return value
        if (!reference.startsWith("#/")) return null
        return reference.removePrefix("#/").split('/').fold(document as Json?) { current, token ->
            current?.get(token.replace("~1", "/").replace("~0", "~"))
        }
    }

    fun schemaViolations(value: Json, schema: Json, location: String): List<String> {
        when (schema) {
            Json.Bool(true) -> return emptyList()
            Json.Bool(false) -> return listOf("$location isn't allowed")
            is Json.Obj -> Unit
            else -> return listOf("$location's schema isn't an object or a boolean")
        }

        val violations = mutableListOf<String>()
        for ((keyword, argument) in schema.fields.toSortedMap()) {
            when (keyword) {
                "\$ref" -> {
                    // In JSON Schema 2020-12, `$ref` applies alongside the keywords next to it.
                    val target = resolve(Json.Obj(mapOf("\$ref" to argument)))
                    if (target == null) {
                        violations += "$location's schema \$ref $argument can't be resolved"
                    } else {
                        violations += schemaViolations(value, target, location)
                    }
                }
                "type" -> {
                    val types = argument.string?.let(::listOf) ?: argument.array.orEmpty().mapNotNull { it.string }
                    if (types.none(value::isOfType)) violations += "$location isn't of type ${types.joinToString(" or ")}: $value"
                }
                "enum" -> if (value !in argument.array.orEmpty()) violations += "$location isn't one of $argument: $value"
                "const" -> if (value != argument) violations += "$location isn't $argument: $value"
                "pattern" -> {
                    val string = value.string ?: continue
                    val regex = try {
                        argument.string?.let(::Regex)
                    } catch (_: PatternSyntaxException) {
                        null
                    }
                    if (regex == null) {
                        violations += "$location's schema pattern $argument isn't a valid regular expression"
                    } else if (!regex.containsMatchIn(string)) {
                        violations += "$location doesn't match ${argument.string}: $value"
                    }
                }
                "minLength" -> {
                    val string = value.string
                    val minimum = argument.number
                    if (string != null && minimum != null && string.codePointCount(0, string.length) < minimum) {
                        violations += "$location is shorter than $argument characters: $value"
                    }
                }
                "minItems" -> {
                    val items = value.array
                    val minimum = argument.number
                    if (items != null && minimum != null && items.size < minimum) violations += "$location has fewer than $argument items"
                }
                "minProperties" -> {
                    val fields = value.obj
                    val minimum = argument.number
                    if (fields != null && minimum != null && fields.size < minimum) violations += "$location has fewer than $argument properties"
                }
                "required" -> {
                    val fields = value.obj ?: continue
                    argument.array.orEmpty().mapNotNull { it.string }.filter { it !in fields }.forEach {
                        violations += "$location is missing the required property $it"
                    }
                }
                "properties" -> {
                    val fields = value.obj ?: continue
                    for ((name, propertySchema) in argument.obj.orEmpty().toSortedMap()) {
                        fields[name]?.let { violations += schemaViolations(it, propertySchema, "$location.$name") }
                    }
                }
                "additionalProperties" -> {
                    val fields = value.obj ?: continue
                    val declared = schema["properties"]?.obj?.keys.orEmpty()
                    for ((name, property) in fields.toSortedMap()) {
                        if (name !in declared) violations += schemaViolations(property, argument, "$location.$name")
                    }
                }
                "items" -> value.array?.forEachIndexed { index, item ->
                    violations += schemaViolations(item, argument, "$location[$index]")
                }
                in annotations -> Unit
                else -> violations += "$location's schema uses $keyword, which OpenApiSpec doesn't support, so add support for it"
            }
        }
        return violations
    }
}
