package dev.dejalango.engine.prompts

/** Role is supplied by capture. Optional language detection changes required fields, never role. */
object CompanionSchemas {
    private const val REPLY = """{"type":"object","properties":{"plain":{"type":"string"},"yours":{"type":"string"},"roman":{"type":"string"},"back":{"type":"string"}},"required":["plain","yours","roman","back"]}"""
    private const val FIELDS = """"heard":{"type":"string"},"heard_l1":{"type":"string"},"roman":{"type":"string"}"""
    fun transcript(detect: Boolean, single: Boolean = false): String {
        val fields = (if (detect) "\"lang\":{\"type\":\"string\"}," else "") + FIELDS +
            if (single) ",\"replies\":{\"type\":\"array\",\"items\":$REPLY}" else ""
        val required = listOfNotNull("lang".takeIf { detect }, "heard", "heard_l1", "roman", "replies".takeIf { single }).joinToString(",") { "\"$it\"" }
        return "{\"type\":\"object\",\"properties\":{$fields},\"required\":[$required]}"
    }
    val replies = """{"type":"object","properties":{"replies":{"type":"array","items":$REPLY}},"required":["replies"]}"""
    const val HINT = """{"type":"object","properties":{"lang":{"type":"string"},"word":{"type":"string"},"roman":{"type":"string"},"meaning":{"type":"string"},"confidence":{"type":"number"}},"required":["lang","word","roman","meaning","confidence"]}"""
}
