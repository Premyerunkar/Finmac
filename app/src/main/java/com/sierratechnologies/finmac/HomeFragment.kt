package com.sierratechnologies.finmac

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private lateinit var viewHeaderBackground: View
    private lateinit var imgLogo: ImageView
    private lateinit var txtSocietyName: TextView
    private lateinit var txtSocietyDetails: TextView
    private lateinit var rvModules: RecyclerView
    private lateinit var btnViewAll: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewHeaderBackground = view.findViewById(R.id.viewHeaderBackground)
        imgLogo = view.findViewById(R.id.imgLogo)
        txtSocietyName = view.findViewById(R.id.txtSocietyName)
        txtSocietyDetails = view.findViewById(R.id.txtSocietyDetails)
        rvModules = view.findViewById(R.id.rvModules)
        btnViewAll = view.findViewById(R.id.btnViewAll)

        setupWindowInsets(view)
        loadLoggedInSocietyProfile()
        setupModulesRecyclerView()
        setupViewAllAction()
    }

    private fun setupWindowInsets(rootView: View) {
        val baseHeaderHeightPx = (240 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            val headerParams = viewHeaderBackground.layoutParams
            if (headerParams != null) {
                headerParams.height = baseHeaderHeightPx + statusBarHeight
                viewHeaderBackground.layoutParams = headerParams
            }

            val logoParams = imgLogo.layoutParams as? ConstraintLayout.LayoutParams
            if (logoParams != null) {
                val logoMarginTop = (26 * resources.displayMetrics.density).toInt()
                logoParams.topMargin = logoMarginTop + statusBarHeight
                imgLogo.layoutParams = logoParams
            }

            insets
        }
    }

    private fun loadLoggedInSocietyProfile() {
        val ctx = context ?: return
        val sharedPref = ctx.getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        val branchCode = sharedPref.getString("SOCIETY_CODE", "") ?: ""
        val savedName = sharedPref.getString("SOCIETY_NAME", "") ?: ""
        val savedAddress = sharedPref.getString("SOCIETY_ADDRESS", "") ?: ""
        val savedCity = sharedPref.getString("SOCIETY_CITY", "") ?: ""

        if (branchCode.isNotEmpty()) {
            val cachedBranches = AppCache.getBranches(ctx)
            val cachedBranch = cachedBranches?.find { it.code.equalsIgnoreCase(branchCode) }
            if (cachedBranch != null) {
                val baseName = cachedBranch.name.ifBlank { savedName.ifBlank { "Society Dashboard" } }
                txtSocietyName.text = if (Util.Session.isSuperAdmin || branchCode.equalsIgnoreCase("SUPERADMIN")) "$baseName 👑 SUPERADMIN" else baseName
                val fullAddress = buildString {
                    if (!cachedBranch.address.isNullOrBlank()) append(cachedBranch.address)
                    if (!cachedBranch.city.isNullOrBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(cachedBranch.city)
                    }
                }
                txtSocietyDetails.text = fullAddress.ifBlank { "Address not available" }
            } else {
                useLocalSessionFallback(savedName, savedAddress, savedCity)
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val list = SupabaseClientManager.postgrest.from("branches")
                        .select { filter { eq("code", branchCode) } }
                        .decodeList<BranchDto>()

                    if (!isAdded || view == null) return@launch

                    if (list.isNotEmpty()) {
                        val branch = list.first()
                        val societyName = branch.name
                        val address = branch.address ?: ""
                        val city = branch.city ?: ""

                        val baseName = societyName.ifBlank { savedName.ifBlank { "Society Dashboard" } }
                        txtSocietyName.text = if (Util.Session.isSuperAdmin || branchCode.equalsIgnoreCase("SUPERADMIN")) "$baseName 👑 SUPERADMIN" else baseName

                        val fullAddress = buildString {
                            if (address.isNotBlank()) append(address)
                            if (city.isNotBlank()) {
                                if (isNotEmpty()) append(", ")
                                append(city)
                            }
                        }
                        txtSocietyDetails.text = fullAddress.ifBlank { "Address not available" }
                    }
                } catch (_: Exception) {}
            }
        } else {
            txtSocietyName.text = if (Util.Session.isSuperAdmin) "Finmac Society 👑 SUPERADMIN" else "Finmac Society"
            txtSocietyDetails.text = "Welcome"
        }
    }

    private fun String.equalsIgnoreCase(other: String?): Boolean {
        return this.equals(other, ignoreCase = true)
    }

    private fun useLocalSessionFallback(savedName: String, savedAddress: String, savedCity: String) {
        txtSocietyName.text = savedName.ifBlank { "Society Dashboard" }

        val fullAddress = buildString {
            if (savedAddress.isNotBlank()) append(savedAddress)
            if (savedCity.isNotBlank()) {
                if (isNotEmpty()) append(", ")
                append(savedCity)
            }
        }
        txtSocietyDetails.text = fullAddress.ifBlank { "Welcome" }
    }

    private fun setupModulesRecyclerView() {
        val dashboardModules = ModuleRepository.getAllModules().take(6)
        val ctx = context ?: return
        rvModules.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)

        rvModules.adapter = ModulesAdapter(dashboardModules) { module ->
            val actCtx = context ?: return@ModulesAdapter
            when (module.title) {
                "Society Layout", "Society Overview", "Society Profile" -> {
                    startActivity(Intent(actCtx, SocietyOverviewActivity::class.java))
                }
                "Member Details", "Members", "Member Registry" -> {
                    startActivity(Intent(actCtx, MemberDetailsActivity::class.java))
                }
                "Entry Book", "Entry", "Entries" -> {
                    startActivity(Intent(actCtx, VoucherTransactionActivity::class.java))
                }
                "Cash Book", "Cashbook", "Cashbook Ledger" -> {
                    startActivity(Intent(actCtx, CashbookActivity::class.java))
                }
                "Ledger Account", "Ledger", "Account Ledger" -> {
                    startActivity(Intent(actCtx, LedgerAccountActivity::class.java))
                }
                else -> {
                    Toast.makeText(actCtx, "${module.title} selected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupViewAllAction() {
        btnViewAll.setOnClickListener {
            val actCtx = context ?: return@setOnClickListener
            startActivity(Intent(actCtx, AllModulesActivity::class.java))
        }
    }
}