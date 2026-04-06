package com.novahorizon.wanderly

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.ActivityMainBinding
import com.novahorizon.wanderly.ui.MainViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNavigation.setupWithNavController(navController)
        
        setupObservers()
        viewModel.checkDailyStreak()
    }

    private fun setupObservers() {
        viewModel.streakMessage.observe(this) { message ->
            message?.let {
                showSnackbar(it, isError = false)
                viewModel.clearStreakMessage()
            }
        }
    }
}
