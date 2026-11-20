package com.olympus.iptv.remind

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R

class RemindersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentHost, RemindersFragment.newInstance())
                .commit()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, RemindersActivity::class.java)
    }
}
