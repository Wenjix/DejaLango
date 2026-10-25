package dev.dejalango.engine.playbook

import dev.dejalango.engine.hud.Reply
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A seeded conversation: the scenes the wearer may walk into, each a graph of moments from [Scene.start]. [l1] is the
 * wearer's language, [l2] the local's. Ids are unique across the whole playbook; [parse] checks that and every link.
 */
data class Playbook(val version: Int, val l1: String, val l2: String, val scenes: List<Scene>) {
    fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }

    companion object {
        const val VERSION = 1

        /**
         * The playbook in [json]. Throws [IllegalArgumentException] naming the scene, moment or id at fault for malformed
         * JSON, a missing or wrong-typed field, another version, a duplicate id, a start or next outside its scene, a
         * moment without a local line or a reply, or blank text.
         */
        fun parse(json: String): Playbook {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("playbook: malformed JSON: ${e.message}", e)
            }
            return Reader().playbook(root)
        }
    }
}

/** One place a conversation happens, such as a restaurant: who the local is there, and the moments from [start]. */
data class Scene(
    val id: String,
    val title: String,
    val setting: String,
    val localRole: String,
    val start: String,
    val moments: List<Moment>,
) {
    fun moment(id: String): Moment? = moments.firstOrNull { it.id == id }
}

/**
 * One step of a scene. [local] is what the local may say here, several phrasings of one [intent]; [replies] are the
 * cards the glasses show; [others] are more ways the wearer might answer, indexed so a reading is known, never shown.
 */
data class Moment(
    val id: String,
    val intent: String,
    val local: List<SeedLine>,
    val replies: List<SeedReply>,
    val others: List<SeedReply>,
) {
    /**
     * [replies] as HUD cards. A seeded reply is its own phrasebook line, so it is both [Reply.plain] and [Reply.yours].
     * A blank roman or back is none, as the model parser has it: a Latin-script L2 needs no romanization.
     */
    fun replyCards(): List<Reply> =
        replies.map { Reply(plain = it.text, yours = it.text, roman = it.roman.ifBlank { null }, back = it.back.ifBlank { null }) }
}

/** A line the local may say, in L2, with its L1 translation. */
data class SeedLine(val id: String, val text: String, val l1: String)

/** A way the wearer might answer, in L2: its romanization, L1 back-translation, and the moment it leads to ("" ends it). */
data class SeedReply(val id: String, val text: String, val roman: String, val back: String, val next: String)

/**
 * Reads one playbook's JSON tree, checking as it goes. An error says where, by id once that is read and by position
 * before: `scene "restaurant" > moment "restaurant.greet" > replies[0]`.
 */
private class Reader {
    /** Every id so far, with where it was first used. */
    private val ids = HashMap<String, String>()

    fun playbook(root: JsonElement): Playbook {
        val o = root as? JsonObject ?: fail("", "not a JSON object")
        val version = o.int("version", "")
        if (version != Playbook.VERSION) fail("", "version $version is not supported, only ${Playbook.VERSION}")
        val l1 = o.str("l1", "")
        val l2 = o.str("l2", "")
        return Playbook(version, l1, l2, o.objects("scenes", "").mapIndexed { i, s -> scene(s, i) })
    }

    private fun scene(o: JsonObject, index: Int): Scene {
        val (id, where) = claim(o, "", "scenes", "scene", index)
        val title = o.str("title", where)
        val setting = o.str("setting", where)
        val localRole = o.str("localRole", where)
        val start = o.str("start", where)
        val items = o.objects("moments", where)
        // Every moment's id before any reply is read, so each next is checked against the whole scene.
        val claimed = items.mapIndexed { j, m -> claim(m, where, "moments", "moment", j) }
        val momentIds = claimed.mapTo(HashSet()) { it.first }
        if (start !in momentIds) fail(where, "start \"$start\" is not a moment of this scene")
        val moments = items.mapIndexed { j, m -> moment(m, claimed[j].first, claimed[j].second, momentIds) }
        return Scene(id, title, setting, localRole, start, moments)
    }

    private fun moment(o: JsonObject, id: String, where: String, sceneMoments: Set<String>): Moment {
        val intent = o.str("intent", where)
        val local = o.objects("local", where).mapIndexed { k, l -> line(l, where, k) }
        val replies = o.objects("replies", where).mapIndexed { k, r -> reply(r, where, "replies", "reply", k, sceneMoments) }
        val others = o.objects("others", where).mapIndexed { k, r -> reply(r, where, "others", "other", k, sceneMoments) }
        if (local.isEmpty()) fail(where, "no local line")
        if (replies.isEmpty()) fail(where, "no reply")
        return Moment(id, intent, local, replies, others)
    }

    private fun line(o: JsonObject, parent: String, index: Int): SeedLine {
        val (id, where) = claim(o, parent, "local", "local", index)
        return SeedLine(id, o.text(where), o.str("l1", where))
    }

    private fun reply(o: JsonObject, parent: String, list: String, label: String, index: Int, sceneMoments: Set<String>): SeedReply {
        val (id, where) = claim(o, parent, list, label, index)
        val text = o.text(where)
        val roman = o.str("roman", where)
        val back = o.str("back", where)
        val next = o.str("next", where)
        if (next.isNotEmpty() && next !in sceneMoments) fail(where, "next \"$next\" is neither \"\" (the end) nor a moment of this scene")
        return SeedReply(id, text, roman, back, next)
    }

    /** [o]'s id, which must be new and not blank, and where [o] is by that id. */
    private fun claim(o: JsonObject, parent: String, list: String, label: String, index: Int): Pair<String, String> {
        val at = path(parent, "$list[$index]")
        val id = o.str("id", at)
        if (id.isBlank()) fail(at, "blank \"id\"")
        ids.putIfAbsent(id, at)?.let { fail(at, "duplicate id \"$id\", also at $it") }
        return id to path(parent, "$label \"$id\"")
    }
}

private fun path(parent: String, child: String): String = if (parent.isEmpty()) child else "$parent > $child"

private fun fail(where: String, what: String): Nothing =
    throw IllegalArgumentException(if (where.isEmpty()) "playbook: $what" else "playbook: $where: $what")

private fun JsonObject.field(key: String, where: String): JsonElement = this[key] ?: fail(where, "missing \"$key\"")

private fun JsonObject.str(key: String, where: String): String =
    (field(key, where) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(where, "\"$key\" must be a string")

/** The line itself: a string, and not a blank one. */
private fun JsonObject.text(where: String): String = str("text", where).also { if (it.isBlank()) fail(where, "blank \"text\"") }

private fun JsonObject.int(key: String, where: String): Int =
    (field(key, where) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull() ?: fail(where, "\"$key\" must be an integer")

private fun JsonObject.objects(key: String, where: String): List<JsonObject> {
    val items = field(key, where) as? JsonArray ?: fail(where, "\"$key\" must be an array")
    return items.mapIndexed { i, e -> e as? JsonObject ?: fail(where, "\"$key\"[$i] must be an object") }
}
