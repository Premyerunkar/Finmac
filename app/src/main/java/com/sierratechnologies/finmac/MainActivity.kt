package com.sierratechnologies.finmac

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.sierratechnologies.finmac.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enforce solid Navy Blue (#052550) Status Bar with Light Icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#052550")),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.TRANSPARENT)
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupWindowInsets()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.tabAccounts.setOnClickListener {
            navController.navigate(R.id.transactionFragment)
        }

        binding.tabTransactions.setOnClickListener {
            navController.navigate(R.id.nav_transactions)
        }

        binding.cardHomeBadge.setOnClickListener {
            navController.navigate(R.id.nav_home)
        }

        binding.tabInvestments.setOnClickListener {
            navController.navigate(R.id.nav_investments)
        }

        binding.tabProfile.setOnClickListener {
            navController.navigate(R.id.nav_profile)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateTabHighlighters(destination.id)
        }
    }

    private fun updateTabHighlighters(destinationId: Int) {
        val activeNavy = Color.parseColor("#052550")
        val inactiveSlate = Color.parseColor("#94A3B8")

        // Reset all secondary tabs
        binding.imgTabAccounts.setColorFilter(inactiveSlate)
        binding.lblTabAccounts.setTextColor(inactiveSlate)

        binding.imgTabTransactions.setColorFilter(inactiveSlate)
        binding.lblTabTransactions.setTextColor(inactiveSlate)

        binding.imgTabInvestments.setColorFilter(inactiveSlate)
        binding.lblTabInvestments.setTextColor(inactiveSlate)

        binding.imgTabProfile.setColorFilter(inactiveSlate)
        binding.lblTabProfile.setTextColor(inactiveSlate)

        // Center Home Badge Styling (RGB 5,37,80 -> #052550)
        if (destinationId == R.id.nav_home) {
            binding.cardHomeBadge.setCardBackgroundColor(activeNavy)
            binding.cardHomeBadge.strokeColor = Color.parseColor("#93C5FD")
        } else {
            binding.cardHomeBadge.setCardBackgroundColor(Color.parseColor("#334155"))
            binding.cardHomeBadge.strokeColor = Color.parseColor("#475569")
        }

        // Highlight Active Secondary Tab in RGB(5,37,80) (#052550)
        when (destinationId) {
            R.id.transactionFragment -> {
                binding.imgTabAccounts.setColorFilter(activeNavy)
                binding.lblTabAccounts.setTextColor(activeNavy)
            }
            R.id.nav_transactions -> {
                binding.imgTabTransactions.setColorFilter(activeNavy)
                binding.lblTabTransactions.setTextColor(activeNavy)
            }
            R.id.nav_investments -> {
                binding.imgTabInvestments.setColorFilter(activeNavy)
                binding.lblTabInvestments.setTextColor(activeNavy)
            }
            R.id.nav_profile -> {
                binding.imgTabProfile.setColorFilter(activeNavy)
                binding.lblTabProfile.setTextColor(activeNavy)
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.cardFloatingNav) { view, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val baseMargin = (16 * resources.displayMetrics.density).toInt()

            val params = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (params != null) {
                params.bottomMargin = baseMargin + navBarHeight
                view.layoutParams = params
            }
            insets
        }
    }
}