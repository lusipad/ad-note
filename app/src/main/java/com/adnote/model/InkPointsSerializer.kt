package com.adnote.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 笔画采样点的紧凑存储格式。
 *
 * 旧格式每个点是一个对象 `{"x":123.456,"y":…,"pressure":…,"t":1727512345678}`，约 65 字节；
 * 新格式把所有点压成一个数字数组：`{"f":4,"d":[x,y,压感,时间, x,y,压感,时间差, …]}`，
 * 坐标保留 2 位小数、压感 3 位，时间从第二个点起记与前一点的差值，每点约 20 字节。
 * 有倾角数据时 f = 5，每点再多一个倾角。
 *
 * 读取时两种格式都认，旧笔记照常打开，下次保存时自动换成新格式。
 */
object InkPointsSerializer : KSerializer<List<InkPoint>> {

    private val legacy = ListSerializer(InkPoint.serializer())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: List<InkPoint>) {
        if (encoder !is JsonEncoder) {
            legacy.serialize(encoder, value)
            return
        }
        encoder.encodeJsonElement(encode(value))
    }

    override fun deserialize(decoder: Decoder): List<InkPoint> {
        if (decoder !is JsonDecoder) return legacy.deserialize(decoder)
        return when (val el = decoder.decodeJsonElement()) {
            is JsonArray -> decoder.json.decodeFromJsonElement(legacy, el)
            is JsonObject -> decode(el)
            else -> emptyList()
        }
    }

    fun encode(points: List<InkPoint>): JsonElement {
        val withTilt = points.any { it.tilt != 0f }
        val fields = if (withTilt) 5 else 4
        val data = ArrayList<JsonElement>(points.size * fields)
        var prevT = 0L
        for ((i, p) in points.withIndex()) {
            data += num(p.x, 100.0)
            data += num(p.y, 100.0)
            data += num(p.pressure, 1000.0)
            data += JsonPrimitive(if (i == 0) p.t else p.t - prevT)
            if (withTilt) data += num(p.tilt, 1000.0)
            prevT = p.t
        }
        return JsonObject(mapOf("f" to JsonPrimitive(fields), "d" to JsonArray(data)))
    }

    fun decode(obj: JsonObject): List<InkPoint> {
        val fields = obj["f"]?.jsonPrimitive?.int ?: 4
        val data = obj["d"]?.jsonArray ?: return emptyList()
        if (fields < 4) return emptyList()
        val n = data.size / fields
        val out = ArrayList<InkPoint>(n)
        var t = 0L
        for (i in 0 until n) {
            val o = i * fields
            val dt = data[o + 3].jsonPrimitive.long
            t = if (i == 0) dt else t + dt
            out += InkPoint(
                x = data[o].jsonPrimitive.double.toFloat(),
                y = data[o + 1].jsonPrimitive.double.toFloat(),
                pressure = data[o + 2].jsonPrimitive.double.toFloat(),
                t = t,
                tilt = if (fields >= 5) data[o + 4].jsonPrimitive.double.toFloat() else 0f,
            )
        }
        return out
    }

    /** 按 [scale] 取整（100 = 两位小数）；整数不写小数点。 */
    private fun num(v: Float, scale: Double): JsonPrimitive {
        val r = (v * scale).roundToLong()
        return if (abs(r) % scale.toLong() == 0L) JsonPrimitive(r / scale.toLong()) else JsonPrimitive(r / scale)
    }
}
