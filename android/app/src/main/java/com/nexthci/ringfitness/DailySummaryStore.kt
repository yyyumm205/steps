package com.nexthci.ringfitness

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class DailySummaryStore(context: Context) {
    private val appContext = context.applicationContext
    private val historyFile = File(appContext.filesDir, "ringfitness_session_history.json")
    private val zone = ZoneId.systemDefault()
    private var history = loadHistory()

    @Synchronized
    fun startSession(
        sessionId: String,
        kind: CaptureKind,
        fileName: String,
        startedAtMs: Long,
        connected: Boolean,
    ) {
        val session = JSONObject()
            .put("id", sessionId)
            .put("signal", kind.fileLabel)
            .put("file", fileName)
            .put("started_at_ms", startedAtMs)
            .put("ended_at_ms", JSONObject.NULL)
            .put("sample_count", 0L)
            .put("connected_intervals", JSONArray())
        if (connected) openInterval(session, startedAtMs)
        sessions().put(session)
        persistAndRegenerate(sessionDates(session, startedAtMs), startedAtMs)
    }

    @Synchronized
    fun updateSampleCounts(counts: Map<String, Long>, nowMs: Long) {
        val dates = linkedSetOf<LocalDate>()
        counts.forEach { (id, count) ->
            findSession(id)?.let { session ->
                session.put("sample_count", count)
                dates += sessionDates(session, nowMs)
            }
        }
        persistAndRegenerate(dates, nowMs)
    }

    @Synchronized
    fun markDisconnected(sessionIds: List<String>, startedAtMs: Long, reason: String) {
        if (sessionIds.isEmpty()) return
        if (latestOpenDisconnection() != null) {
            updateSampleCounts(emptyMap(), startedAtMs)
            return
        }
        val signalNames = JSONArray()
        val dates = linkedSetOf<LocalDate>()
        sessionIds.forEach { id ->
            findSession(id)?.let { session ->
                closeOpenInterval(session, startedAtMs)
                signalNames.put(session.getString("signal"))
                dates += sessionDates(session, startedAtMs)
            }
        }
        disconnections().put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("started_at_ms", startedAtMs)
                .put("ended_at_ms", JSONObject.NULL)
                .put("signals", signalNames)
                .put("reason", reason),
        )
        dates += localDate(startedAtMs)
        persistAndRegenerate(dates, startedAtMs)
    }

    @Synchronized
    fun markReconnected(sessionIds: List<String>, reconnectedAtMs: Long) {
        val dates = linkedSetOf<LocalDate>()
        latestOpenDisconnection()?.let { event ->
            event.put("ended_at_ms", reconnectedAtMs)
            dates += datesBetween(event.getLong("started_at_ms"), reconnectedAtMs)
        }
        sessionIds.forEach { id ->
            findSession(id)?.let { session ->
                openInterval(session, reconnectedAtMs)
                dates += sessionDates(session, reconnectedAtMs)
            }
        }
        persistAndRegenerate(dates, reconnectedAtMs)
    }

    @Synchronized
    fun closeOpenDisconnection(endedAtMs: Long) {
        val event = latestOpenDisconnection() ?: return
        event.put("ended_at_ms", endedAtMs)
        persistAndRegenerate(datesBetween(event.getLong("started_at_ms"), endedAtMs), endedAtMs)
    }

    @Synchronized
    fun stopSession(sessionId: String, endedAtMs: Long, sampleCount: Long) {
        val session = findSession(sessionId) ?: return
        closeOpenInterval(session, endedAtMs)
        session.put("ended_at_ms", endedAtMs)
        session.put("sample_count", sampleCount)
        persistAndRegenerate(sessionDates(session, endedAtMs), endedAtMs)
    }

    @Synchronized
    fun currentDayJson(nowMs: Long = System.currentTimeMillis()): String {
        val date = localDate(nowMs)
        regenerate(date, nowMs)
        return buildSummary(date, nowMs).toString(2)
    }

    private fun loadHistory(): JSONObject = runCatching {
        if (historyFile.exists()) JSONObject(historyFile.readText(Charsets.UTF_8)) else newHistory()
    }.getOrElse { newHistory() }

    private fun newHistory() = JSONObject()
        .put("sessions", JSONArray())
        .put("disconnections", JSONArray())

    private fun sessions(): JSONArray = history.getJSONArray("sessions")
    private fun disconnections(): JSONArray = history.getJSONArray("disconnections")

    private fun findSession(id: String): JSONObject? {
        val array = sessions()
        for (index in 0 until array.length()) {
            val session = array.getJSONObject(index)
            if (session.getString("id") == id) return session
        }
        return null
    }

    private fun latestOpenDisconnection(): JSONObject? {
        val array = disconnections()
        for (index in array.length() - 1 downTo 0) {
            val event = array.getJSONObject(index)
            if (event.isNull("ended_at_ms")) return event
        }
        return null
    }

    private fun openInterval(session: JSONObject, startMs: Long) {
        val intervals = session.getJSONArray("connected_intervals")
        if (intervals.length() > 0 && intervals.getJSONObject(intervals.length() - 1).isNull("ended_at_ms")) return
        intervals.put(
            JSONObject()
                .put("started_at_ms", startMs)
                .put("ended_at_ms", JSONObject.NULL),
        )
    }

    private fun closeOpenInterval(session: JSONObject, endMs: Long) {
        val intervals = session.getJSONArray("connected_intervals")
        if (intervals.length() == 0) return
        val last = intervals.getJSONObject(intervals.length() - 1)
        if (last.isNull("ended_at_ms")) last.put("ended_at_ms", endMs)
    }

    private fun persistAndRegenerate(dates: Set<LocalDate>, nowMs: Long) {
        historyFile.writeText(history.toString(), Charsets.UTF_8)
        dates.forEach { regenerate(it, nowMs) }
    }

    private fun regenerate(date: LocalDate, nowMs: Long) {
        val json = buildSummary(date, nowMs).toString(2)
        val resolver = appContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${BuildConfig.PUBLIC_DATA_DIRECTORY}/$date/"
        val uri = findSummaryUri(relativePath) ?: resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, SUMMARY_FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            },
        ) ?: error("无法创建 $relativePath$SUMMARY_FILE")
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8).use { writer ->
            requireNotNull(writer) { "无法写入每日总结" }
            writer.write(json)
        }
    }

    private fun findSummaryUri(relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(SUMMARY_FILE, relativePath)
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0).toString(),
                )
            }
        }
        return null
    }

    private fun buildSummary(date: LocalDate, nowMs: Long): JSONObject {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val signalObjects = linkedMapOf<String, JSONObject>()
        CaptureKind.entries.forEach { kind ->
            signalObjects[kind.fileLabel] = JSONObject()
                .put("total_capture_ms", 0L)
                .put("total_capture_duration", "00:00:00")
                .put("sessions", JSONArray())
        }

        val allSessions = sessions()
        for (index in 0 until allSessions.length()) {
            val session = allSessions.getJSONObject(index)
            val signal = session.getString("signal")
            val clippedIntervals = JSONArray()
            var captureMs = 0L
            val intervals = session.getJSONArray("connected_intervals")
            for (intervalIndex in 0 until intervals.length()) {
                val interval = intervals.getJSONObject(intervalIndex)
                val start = interval.getLong("started_at_ms")
                val end = if (interval.isNull("ended_at_ms")) nowMs else interval.getLong("ended_at_ms")
                val clippedStart = maxOf(start, dayStart)
                val clippedEnd = minOf(end, dayEnd)
                if (clippedEnd > clippedStart) {
                    captureMs += clippedEnd - clippedStart
                    clippedIntervals.put(
                        JSONObject()
                            .put("started_at", iso(clippedStart))
                            .put("ended_at", if (interval.isNull("ended_at_ms") && end < dayEnd) JSONObject.NULL else iso(clippedEnd))
                            .put("duration_ms", clippedEnd - clippedStart),
                    )
                }
            }
            if (captureMs == 0L && clippedIntervals.length() == 0) continue
            val signalObject = signalObjects.getOrPut(signal) {
                JSONObject().put("total_capture_ms", 0L).put("sessions", JSONArray())
            }
            signalObject.put("total_capture_ms", signalObject.getLong("total_capture_ms") + captureMs)
            signalObject.getJSONArray("sessions").put(
                JSONObject()
                    .put("session_id", session.getString("id"))
                    .put("file", session.getString("file"))
                    .put("manual_started_at", iso(session.getLong("started_at_ms")))
                    .put("manual_ended_at", if (session.isNull("ended_at_ms")) JSONObject.NULL else iso(session.getLong("ended_at_ms")))
                    .put("capture_ms_today", captureMs)
                    .put("total_sample_count", session.optLong("sample_count", 0L))
                    .put("connected_intervals", clippedIntervals),
            )
        }
        signalObjects.values.forEach { signal ->
            signal.put("total_capture_duration", formatDuration(signal.getLong("total_capture_ms")))
        }

        val disconnectArray = JSONArray()
        val allDisconnections = disconnections()
        var totalDisconnectedMs = 0L
        for (index in 0 until allDisconnections.length()) {
            val event = allDisconnections.getJSONObject(index)
            val start = event.getLong("started_at_ms")
            val open = event.isNull("ended_at_ms")
            val end = if (open) nowMs else event.getLong("ended_at_ms")
            val clippedStart = maxOf(start, dayStart)
            val clippedEnd = minOf(end, dayEnd)
            if (clippedEnd <= clippedStart) continue
            val duration = clippedEnd - clippedStart
            totalDisconnectedMs += duration
            disconnectArray.put(
                JSONObject()
                    .put("started_at", iso(clippedStart))
                    .put("ended_at", if (open && end < dayEnd) JSONObject.NULL else iso(clippedEnd))
                    .put("duration_ms", duration)
                    .put("duration", formatDuration(duration))
                    .put("signals", event.getJSONArray("signals"))
                    .put("reason", event.optString("reason", "BLE disconnected"))
                    .put("ongoing", open),
            )
        }

        return JSONObject()
            .put("date", date.toString())
            .put("timezone", zone.id)
            .put("updated_at", iso(nowMs))
            .put("signals", JSONObject(signalObjects as Map<*, *>))
            .put("disconnections", disconnectArray)
            .put("total_disconnected_ms", totalDisconnectedMs)
            .put("total_disconnected_duration", formatDuration(totalDisconnectedMs))
    }

    private fun sessionDates(session: JSONObject, nowMs: Long): Set<LocalDate> {
        val start = session.getLong("started_at_ms")
        val end = if (session.isNull("ended_at_ms")) nowMs else session.getLong("ended_at_ms")
        return datesBetween(start, end)
    }

    private fun datesBetween(startMs: Long, endMs: Long): Set<LocalDate> {
        val dates = linkedSetOf<LocalDate>()
        var date = localDate(startMs)
        val finalDate = localDate(maxOf(startMs, endMs))
        while (!date.isAfter(finalDate)) {
            dates += date
            date = date.plusDays(1)
        }
        return dates
    }

    private fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    companion object {
        private const val SUMMARY_FILE = "summary.json"
    }
}
