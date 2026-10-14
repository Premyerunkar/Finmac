package com.sierratechnologies.finmac

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.sierratechnologies.finmac.databinding.ActivityAllModulesBinding

class AllModulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllModulesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllModulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupFullModulesGrid()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Dashboard Overview"

        // Handle back button clicks cleanly
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupFullModulesGrid() {
        // Pull all 19 structural feature components from the dataset repository
        val allModulesList = ModuleRepository.getAllModules()

        // Enterprise horizontal list configuration
        binding.rvAllModules.layoutManager = LinearLayoutManager(this)
        binding.rvAllModules.adapter = ModulesAdapter(allModulesList)
    }
}