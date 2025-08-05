package com.example.realtime

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.realtime.databinding.ActivityMainBinding
import com.example.realtime.DocumentFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ShortFragment())
            .commit()

        binding.btnShortText.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ShortFragment())
                .commit()
        }

        binding.btnDocument.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DocumentFragment())
                .commit()
        }


    }
}
