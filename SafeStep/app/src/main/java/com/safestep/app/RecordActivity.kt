package com.safestep.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME  = "safestep_records"
        private const val KEY_RECORDS = "walk_records"
        private const val MAX_RECORDS = 30

        /** MapActivity 도착 시 호출 */
        fun saveRecord(ctx: Context, destName: String, distStr: String, elapsedMin: Int) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr   = try { JSONArray(prefs.getString(KEY_RECORDS, "[]")) } catch (e: Exception) { JSONArray() }

            val dateStr = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
                .format(Date(System.currentTimeMillis()))

            val obj = JSONObject().apply {
                put("dest", destName)
                put("dist", distStr)
                put("min",  elapsedMin)
                put("date", dateStr)
            }

            // 최신 기록이 맨 앞
            val newArr = JSONArray()
            newArr.put(obj)
            for (i in 0 until minOf(arr.length(), MAX_RECORDS - 1)) newArr.put(arr.get(i))

            prefs.edit().putString(KEY_RECORDS, newArr.toString()).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)
        setupBottomNav()
        loadRecords()
    }

    private fun loadRecords() {
        val prefs   = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr     = try { JSONArray(prefs.getString(KEY_RECORDS, "[]")) } catch (e: Exception) { JSONArray() }
        val emptyView  = findViewById<LinearLayout>(R.id.emptyView)
        val recordList = findViewById<LinearLayout>(R.id.recordList)

        if (arr.length() == 0) {
            emptyView.visibility  = View.VISIBLE
            recordList.visibility = View.GONE
            return
        }

        emptyView.visibility  = View.GONE
        recordList.visibility = View.VISIBLE
        recordList.removeAllViews()

        for (i in 0 until arr.length()) {
            val obj  = arr.getJSONObject(i)
            val dest = obj.optString("dest", "알 수 없음")
            val dist = obj.optString("dist", "-")
            val min  = obj.optInt("min", 0)
            val date = obj.optString("date", "")

            val view = LayoutInflater.from(this).inflate(R.layout.item_record, recordList, false)
            view.findViewById<TextView>(R.id.recordDest).text = dest
            view.findViewById<TextView>(R.id.recordDate).text = date
            view.findViewById<TextView>(R.id.recordInfo).text =
                if (min > 0) "$dist · ${min}분 소요" else dist

            recordList.addView(view)
        }
    }

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_record
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home     -> { startActivity(Intent(this, SplashActivity::class.java)); finish(); true }
                R.id.nav_guide    -> { startActivity(Intent(this, MapActivity::class.java)); finish(); true }
                R.id.nav_record   -> true
                R.id.nav_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); finish(); true }
                else -> false
            }
        }
    }
}
