package dev.vantara.replicatorops.slicer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Cura's setting definitions (fdmprinter + fdmextruder, compiled at build time by
 * native/curaengine/tools/compile_defs.py) and an evaluator for their formulas.
 *
 * Cura's front end works out every setting from a few hundred small Python expressions
 * (e.g. infill line distance from density and line width). CuraEngine itself only takes
 * plain values, so the app does that work here: [resolve] fixes the overrides, then
 * evaluates each formula with Python's rules (int/float, truthiness, banker's rounding…).
 * native/curaengine/tools/resolve.py does the same in Python; CI checks they agree.
 */
class CuraSettings(table: JsonObject) {
    private class Def(val type: String?, val default: Any?, val formula: JsonElement?)

    private val defs: Map<String, Def> = table["settings"]!!.jsonObject.mapValues { (_, e) ->
        val o = e.jsonObject
        Def(o["t"]?.jsonPrimitive?.contentOrNull, o["d"]?.let(::fromJson), o["v"])
    }
    val version: String? = (table["version"] as? JsonPrimitive)?.contentOrNull
    val keys: Set<String> get() = defs.keys

    /** Every setting's value with [overrides] applied. Values: Boolean, Long, Double, String, List, null. */
    fun resolve(overrides: Map<String, Any?>): Map<String, Any?> {
        val r = Resolver(overrides)
        return defs.keys.associateWith { r.get(it) }
    }

    private inner class Resolver(private val over: Map<String, Any?>) {
        private val cache = HashMap<String, Any?>()
        private val stack = HashSet<String>()

        fun get(key: String): Any? {
            if (cache.containsKey(key)) return cache[key]
            val d = defs[key] ?: throw NoSuchElementException(key)
            val v = when {
                over.containsKey(key) -> typed(d.type, over[key])
                d.formula != null && key !in stack -> {
                    stack += key
                    try {
                        typed(d.type, eval(d.formula, emptyMap()))
                    } catch (e: Exception) {
                        typed(d.type, d.default)
                    } finally {
                        stack -= key
                    }
                }
                else -> typed(d.type, d.default)
            }
            cache[key] = v
            return v
        }

        private fun eval(n: JsonElement, locals: Map<String, Any?>): Any? {
            if (n is JsonPrimitive || n is JsonNull) return fromJson(n)
            val a = n.jsonArray
            fun at(i: Int) = a[i]
            return when (a[0].jsonPrimitive.content) {
                "c" -> fromJson(at(1))
                "n" -> name(at(1).jsonPrimitive.content, locals)
                "a" -> {
                    val obj = eval(at(1), locals)
                    val attr = at(2).jsonPrimitive.content
                    if (obj == MathModule) MathModule.attr(attr) else throw IllegalStateException("attribute $attr")
                }
                "call" -> {
                    val f = eval(at(1), locals)
                    val args = at(2).jsonArray.map { eval(it, locals) }
                    call(f, args)
                }
                "bin" -> Py.bin(at(1).jsonPrimitive.content, eval(at(2), locals), eval(at(3), locals))
                "un" -> {
                    val x = eval(at(2), locals)
                    when (at(1).jsonPrimitive.content) {
                        "not" -> !Py.truthy(x)
                        "-" -> Py.bin("-", 0L, x)
                        else -> Py.bin("+", 0L, x)
                    }
                }
                "bool" -> {
                    val and = at(1).jsonPrimitive.content == "and"
                    var last: Any? = null
                    for (e in at(2).jsonArray) {
                        last = eval(e, locals)
                        if (and != Py.truthy(last)) return last
                    }
                    last
                }
                "cmp" -> {
                    var left = eval(at(1), locals)
                    val ops = at(2).jsonArray
                    val rights = at(3).jsonArray
                    for (i in ops.indices) {
                        val right = eval(rights[i], locals)
                        if (!Py.compare(ops[i].jsonPrimitive.content, left, right)) return false
                        left = right
                    }
                    true
                }
                "if" -> if (Py.truthy(eval(at(1), locals))) eval(at(2), locals) else eval(at(3), locals)
                "list" -> at(1).jsonArray.map { eval(it, locals) }
                "sub" -> Py.index(eval(at(1), locals), eval(at(2), locals))
                "gen" -> {
                    val variable = at(2).jsonPrimitive.content
                    val conds = at(4).jsonArray
                    Py.iter(eval(at(3), locals)).mapNotNull { item ->
                        val scope = locals + (variable to item)
                        if (conds.all { Py.truthy(eval(it, scope)) }) Box(eval(at(1), scope)) else null
                    }.map { it.v }
                }
                else -> throw IllegalStateException("node ${a[0]}")
            }
        }

        private fun name(id: String, locals: Map<String, Any?>): Any? = when {
            locals.containsKey(id) -> locals[id]
            defs.containsKey(id) -> get(id)
            id == "math" -> MathModule
            id == "True" -> true
            id == "False" -> false
            id == "None" -> null
            else -> Builtin(id)
        }

        private fun call(f: Any?, args: List<Any?>): Any? {
            if (f is MathFn) return f.call(args)
            val name = (f as? Builtin)?.name ?: throw IllegalStateException("not callable")
            return when (name) {
                "resolveOrValue" -> get(args[0] as String)
                "extruderValue" -> get(args[1] as String)
                "extruderValues" -> listOf(get(args[0] as String))
                "defaultExtruderPosition" -> "0"
                "anyExtruderWithMaterial" -> false
                "anyExtruderNrWithOrDefault" -> "0"
                "valueFromContainer" -> 0L
                "max", "min" -> {
                    val items = if (args.size == 1) Py.iter(args[0]) else args
                    require(items.isNotEmpty())
                    items.reduce { best, x ->
                        if (name == "max") { if (Py.compare(">", x, best)) x else best }
                        else { if (Py.compare("<", x, best)) x else best }
                    }
                }
                "round" -> Py.round(args[0], args.getOrNull(1))
                "sum" -> Py.iter(args[0]).fold(0L as Any?) { acc, x -> Py.bin("+", acc, x) }
                "len" -> when (val x = args[0]) { is String -> x.length.toLong(); else -> Py.iter(x).size.toLong() }
                "any" -> Py.iter(args[0]).any { Py.truthy(it) }
                "all" -> Py.iter(args[0]).all { Py.truthy(it) }
                "abs" -> Py.bin("*", args[0], if (Py.compare("<", args[0], 0L)) -1L else 1L)
                "int" -> Py.toInt(args[0])
                "float" -> Py.toFloat(args[0])
                "str" -> Py.str(args[0])
                "bool" -> Py.truthy(args[0])
                "map" -> Py.iter(args[1]).map { call(args[0], listOf(it)) }
                else -> throw IllegalStateException("unknown function $name")
            }
        }
    }

    private class Box(val v: Any?)
    private data class Builtin(val name: String)
    private class MathFn(val call: (List<Any?>) -> Any?)

    private object MathModule {
        fun attr(name: String): Any = when (name) {
            "pi" -> Math.PI
            "e" -> Math.E
            "sqrt" -> MathFn { Math.sqrt(Py.num(it[0])) }
            "ceil" -> MathFn { Math.ceil(Py.num(it[0])).toLong() }
            "floor" -> MathFn { floor(Py.num(it[0])).toLong() }
            "log" -> MathFn { if (it.size > 1) Math.log(Py.num(it[0])) / Math.log(Py.num(it[1])) else Math.log(Py.num(it[0])) }
            "log10" -> MathFn { Math.log10(Py.num(it[0])) }
            "exp" -> MathFn { Math.exp(Py.num(it[0])) }
            "cos" -> MathFn { Math.cos(Py.num(it[0])) }
            "sin" -> MathFn { Math.sin(Py.num(it[0])) }
            "tan" -> MathFn { Math.tan(Py.num(it[0])) }
            "atan" -> MathFn { Math.atan(Py.num(it[0])) }
            "radians" -> MathFn { Math.toRadians(Py.num(it[0])) }
            "degrees" -> MathFn { Math.toDegrees(Py.num(it[0])) }
            "pow" -> MathFn { Py.num(it[0]).pow(Py.num(it[1])) }
            "fabs" -> MathFn { abs(Py.num(it[0])) }
            else -> throw IllegalStateException("math.$name")
        }
    }

    companion object {
        fun load(text: String) = CuraSettings(Json.parseToJsonElement(text).jsonObject)

        fun fromJson(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null && !e.content.contains('.') && !e.content.contains('e', true) -> e.longOrNull
                else -> e.content.toDouble()
            }
            is JsonArray -> e.map { fromJson(it) }
            is JsonObject -> e.mapValues { fromJson(it.value) }
        }

        /** Cura's conversion of a formula result to the setting's type. */
        fun typed(type: String?, v: Any?): Any? = if (v == null) null else when (type) {
            "float" -> Py.toFloat(v)
            "int" -> Py.toInt(v)
            "bool" -> Py.truthy(v)
            "extruder", "optional_extruder" -> Py.str(v)
            else -> v
        }

        /** JSON for CuraEngine's -j: one flat "category" holding every value. */
        fun toEngineJson(values: Map<String, Any?>, types: CuraSettings): JsonObject {
            fun js(v: Any?): JsonElement = when (v) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(v)
                is Long -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                is Double -> JsonPrimitive(v)
                is String -> JsonPrimitive(v)
                is List<*> -> JsonArray(v.map { js(it) })
                else -> JsonPrimitive(v.toString())
            }
            val children = values.filterValues { it != null }.mapValues { (k, v) ->
                JsonObject(buildMap {
                    types.defs[k]?.type?.let { put("type", JsonPrimitive(it)) }
                    put("default_value", js(v))
                })
            }
            return JsonObject(mapOf(
                "version" to JsonPrimitive(2),
                "name" to JsonPrimitive("Replicator Ops"),
                "metadata" to JsonObject(emptyMap()),
                "settings" to JsonObject(mapOf("resolved" to JsonObject(mapOf(
                    "type" to JsonPrimitive("category"),
                    "children" to JsonObject(children),
                )))),
            ))
        }
    }
}

/** The bits of Python's semantics Cura's formulas rely on. */
internal object Py {
    fun truthy(x: Any?): Boolean = when (x) {
        null -> false
        is Boolean -> x
        is Long -> x != 0L
        is Int -> x != 0
        is Double -> x != 0.0
        is String -> x.isNotEmpty()
        is List<*> -> x.isNotEmpty()
        is Map<*, *> -> x.isNotEmpty()
        else -> true
    }

    private fun isNum(x: Any?) = x is Long || x is Int || x is Double || x is Boolean
    private fun isInt(x: Any?) = x is Long || x is Int || x is Boolean

    fun num(x: Any?): Double = when (x) {
        is Long -> x.toDouble()
        is Int -> x.toDouble()
        is Double -> x
        is Boolean -> if (x) 1.0 else 0.0
        is String -> x.trim().toDouble()
        else -> throw IllegalArgumentException("not a number: $x")
    }

    fun long(x: Any?): Long = when (x) {
        is Long -> x
        is Int -> x.toLong()
        is Boolean -> if (x) 1L else 0L
        else -> num(x).toLong()
    }

    fun bin(op: String, a: Any?, b: Any?): Any? {
        if (op == "+" && a is String && b is String) return a + b
        if (op == "+" && a is List<*> && b is List<*>) return a + b
        if (op == "*" && a is String && isInt(b)) return a.repeat(long(b).toInt().coerceAtLeast(0))
        if (op == "%" && a is String) throw IllegalArgumentException("string formatting")
        require(isNum(a) && isNum(b)) { "unsupported operands for $op: $a, $b" }
        val ints = isInt(a) && isInt(b)
        return when (op) {
            "+" -> if (ints) long(a) + long(b) else num(a) + num(b)
            "-" -> if (ints) long(a) - long(b) else num(a) - num(b)
            "*" -> if (ints) long(a) * long(b) else num(a) * num(b)
            "/" -> num(a) / num(b).also { if (it == 0.0) throw ArithmeticException("division by zero") }
            "//" -> if (ints) Math.floorDiv(long(a), long(b)) else floor(num(a) / num(b))
            "%" -> if (ints) Math.floorMod(long(a), long(b)) else {
                val m = num(a) % num(b); if (m != 0.0 && (m < 0) != (num(b) < 0)) m + num(b) else m
            }
            "**" -> if (ints && long(b) >= 0) num(a).pow(num(b)).toLong() else num(a).pow(num(b))
            else -> throw IllegalArgumentException(op)
        }
    }

    fun eq(a: Any?, b: Any?): Boolean = when {
        isNum(a) && isNum(b) -> num(a) == num(b)
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { eq(a[it], b[it]) }
        else -> a == b
    }

    fun compare(op: String, a: Any?, b: Any?): Boolean = when (op) {
        "==" -> eq(a, b)
        "!=" -> !eq(a, b)
        "in" -> contains(b, a)
        "not in" -> !contains(b, a)
        else -> {
            val c = when {
                isNum(a) && isNum(b) -> num(a).compareTo(num(b))
                a is String && b is String -> a.compareTo(b)
                else -> throw IllegalArgumentException("cannot order $a and $b")
            }
            when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; ">=" -> c >= 0; else -> throw IllegalArgumentException(op) }
        }
    }

    private fun contains(container: Any?, x: Any?): Boolean = when (container) {
        is String -> x is String && container.contains(x)
        is List<*> -> container.any { eq(it, x) }
        is Map<*, *> -> container.containsKey(x)
        else -> throw IllegalArgumentException("not a container")
    }

    fun iter(x: Any?): List<Any?> = when (x) {
        is List<*> -> x
        is String -> x.map { it.toString() }
        is Map<*, *> -> x.keys.toList()
        else -> throw IllegalArgumentException("not iterable: $x")
    }

    fun index(x: Any?, i: Any?): Any? {
        val list = when (x) { is String -> x.map { it.toString() }; is List<*> -> x; else -> throw IllegalArgumentException("not indexable") }
        var n = long(i).toInt()
        if (n < 0) n += list.size
        return list[n]
    }

    /** Python's round(): half to even, on the exact binary value. */
    fun round(x: Any?, digits: Any?): Any? {
        if (digits == null) return if (isInt(x)) long(x) else Math.rint(num(x)).toLong()
        val d = long(digits).toInt()
        if (isInt(x)) return long(x)
        val v = num(x)
        if (v.isNaN() || v.isInfinite()) return v
        return BigDecimal(v).setScale(d, RoundingMode.HALF_EVEN).toDouble()
    }

    fun toInt(x: Any?): Long = when (x) {
        is String -> x.trim().toLongOrNull() ?: x.trim().toDouble().toLong()
        else -> long(x)
    }

    fun toFloat(x: Any?): Double = num(x)

    fun str(x: Any?): String = when (x) {
        null -> "None"
        is Boolean -> if (x) "True" else "False"
        is Double -> if (x == floor(x) && abs(x) < 1e16) "${x.toLong()}.0" else x.toString()
        else -> x.toString()
    }
}
