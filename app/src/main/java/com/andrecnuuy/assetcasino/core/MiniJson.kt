package com.andrecnuuy.assetcasino.core

typealias JObj = Map<String, Any?>
typealias JArr = List<Any?>

class JsonParseException(message: String) : RuntimeException(message)

/**
 * Tiny dependency-free JSON reader/writer. Objects become LinkedHashMap, arrays ArrayList,
 * integers Long, other numbers Double. Used for Danbooru responses, the DAO backup and our own files.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (p.pos != text.length) throw JsonParseException("Unexpected trailing data at ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value)
            is Long -> sb.append(value)
            is Double -> writeDouble(value, sb)
            is Float -> writeDouble(value.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            else -> quote(value.toString(), sb)
        }
    }

    private fun writeDouble(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null")
        } else if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            sb.append(d.toLong())
        } else {
            sb.append(d.toString())
        }
    }

    fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        sb.append(String.format("%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun readValue(): Any? {
            if (pos >= s.length) throw JsonParseException("Unexpected end of input")
            val c = s[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || (c in '0'..'9') -> readNumber()
                else -> throw JsonParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonParseException("Bad literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonParseException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("Expected ':' at $pos")
                pos++
                skipWs()
                map[key] = readValue()
                skipWs()
                if (pos >= s.length) throw JsonParseException("Unterminated object")
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == '}') {
                    pos++
                    return map
                } else {
                    throw JsonParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                if (pos >= s.length) throw JsonParseException("Unterminated array")
                val c = s[pos]
                if (c == ',') {
                    pos++
                } else if (c == ']') {
                    pos++
                    return list
                } else {
                    throw JsonParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val start = pos
            var i = pos
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    val r = s.substring(start, i)
                    pos = i + 1
                    return r
                }
                if (c == '\\') break
                i++
            }
            if (i >= s.length) throw JsonParseException("Unterminated string")
            val sb = StringBuilder()
            sb.append(s, start, i)
            pos = i
            while (pos < s.length) {
                val c = s[pos]
                if (c == '"') {
                    pos++
                    return sb.toString()
                }
                if (c == '\\') {
                    pos++
                    if (pos >= s.length) throw JsonParseException("Bad escape")
                    val e = s[pos]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= s.length) throw JsonParseException("Bad unicode escape")
                            val code = s.substring(pos + 1, pos + 5).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw JsonParseException("Bad escape '\\$e' at $pos")
                    }
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw JsonParseException("Unterminated string")
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (!isFloat) {
                val l = t.toLongOrNull()
                if (l != null) return l
            }
            return t.toDoubleOrNull() ?: throw JsonParseException("Bad number '$t' at $start")
        }
    }
}

// ---- typed accessors -------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): JObj? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): JArr? = this as? List<Any?>

fun Any?.asLong(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is String -> this.toLongOrNull()
    else -> null
}

fun Any?.asDouble(): Double? = when (this) {
    is Double -> this
    is Long -> this.toDouble()
    is Int -> this.toDouble()
    is String -> this.toDoubleOrNull()
    else -> null
}

fun JObj?.str(key: String): String? = this?.get(key) as? String
fun JObj?.long(key: String): Long? = this?.get(key).asLong()
fun JObj?.int(key: String): Int? = this?.get(key).asLong()?.toInt()
fun JObj?.dbl(key: String): Double? = this?.get(key).asDouble()
fun JObj?.bool(key: String): Boolean? = this?.get(key) as? Boolean
fun JObj?.obj(key: String): JObj? = this?.get(key).asObj()
fun JObj?.arr(key: String): JArr? = this?.get(key).asArr()
