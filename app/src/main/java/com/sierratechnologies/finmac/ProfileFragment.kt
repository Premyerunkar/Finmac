package com.sierratechnologies.finmac

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import java.util.Locale

class ProfileFragment : Fragment() {

    private val targetBranchCode by lazy {
        val ctx = context ?: activity?.applicationContext
        val sharedPref = ctx?.getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        sharedPref?.getString("SOCIETY_CODE", "")?.trim() ?: ""
    }

    private lateinit var lblProfileSocietyName: TextView
    private lateinit var lblProfileRegNo: TextView
    private lateinit var lblProfileAddress: TextView
    private lateinit var lblAvatarInitial: TextView
    private lateinit var lblWorkingPeriodMetric: TextView

    private lateinit var menuSocietyOverview: LinearLayout
    private lateinit var menuAdminManagement: LinearLayout
    private lateinit var btnLogout: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_profile, container, false)

        initViews(root)
        setupClickListeners()
        fetchProfileDataFromCloud()

        return root
    }

    private fun initViews(v: View) {
        lblProfileSocietyName = v.findViewById(R.id.lblProfileSocietyName)
        lblProfileRegNo = v.findViewById(R.id.lblProfileRegNo)
        lblProfileAddress = v.findViewById(R.id.lblProfileAddress)
        lblAvatarInitial = v.findViewById(R.id.lblAvatarInitial)
        lblWorkingPeriodMetric = v.findViewById(R.id.lblWorkingPeriodMetric)

        menuSocietyOverview = v.findViewById(R.id.menuSocietyOverview)
        menuAdminManagement = v.findViewById(R.id.menuAdminManagement)
        btnLogout = v.findViewById(R.id.btnLogout)

        val headerBanner = v.findViewById<View>(R.id.headerProfileBanner)
        if (headerBanner != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(headerBanner) { view, insets ->
                val statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                val currentDp = (20 * resources.displayMetrics.density).toInt()
                view.setPadding(view.paddingLeft, statusBarHeight + currentDp, view.paddingRight, view.paddingBottom)
                insets
            }
        }
    }

    private fun setupClickListeners() {
        menuSocietyOverview.setOnClickListener {
            if (targetBranchCode.isBlank()) {
                Toast.makeText(requireContext(), "Active session society code missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), SocietyOverviewActivity::class.java).apply {
                putExtra("SOCIETY_CODE", targetBranchCode)
            }
            startActivity(intent)
        }

        menuAdminManagement.setOnClickListener {
            if (targetBranchCode.isBlank()) {
                Toast.makeText(requireContext(), "Active session society code missing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), AdminManagementActivity::class.java).apply {
                putExtra("SOCIETY_CODE", targetBranchCode)
            }
            startActivity(intent)
        }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Terminate Session")
                .setMessage("Are you sure you want to log out of the active Finmac operational ledger?")
                .setPositiveButton("Logout") { _, _ ->
                    val sharedPref = requireContext().getSharedPreferences("UserSession", Context.MODE_PRIVATE)
                    sharedPref.edit().clear().apply()

                    val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    requireActivity().finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun fetchProfileDataFromCloud() {
        if (targetBranchCode.isBlank()) return

        val ctx = context ?: return
        val sharedPref = ctx.getSharedPreferences("UserSession", Context.MODE_PRIVATE)
        val savedName = sharedPref.getString("SOCIETY_NAME", "") ?: ""
        val savedAddress = sharedPref.getString("SOCIETY_ADDRESS", "") ?: ""
        val savedCity = sharedPref.getString("SOCIETY_CITY", "") ?: ""

        // 1. Instant 0ms Load from AppCache or UserSession
        val cachedBranches = AppCache.getBranches(ctx)
        val cachedBranch = cachedBranches?.find { it.code.equalsIgnoreCase(targetBranchCode) }

        val sName = cachedBranch?.name?.ifBlank { null } ?: savedName.ifBlank { "Finmac Society" }
        val regNo = cachedBranch?.registrationNo ?: "REG-MH-26/X4911"
        val address = cachedBranch?.address?.ifBlank { null } ?: savedAddress.ifBlank { "Head Office Registry" }
        
        // Auto-fetch Working Period from Society Layout Committee Working Period
        val period = cachedBranch?.committeePeriod?.ifBlank { null }
            ?: deriveWorkingPeriodFromElectedDate(cachedBranch?.committeeElectedOn)
            ?: "2023 - 2028"

        lblProfileSocietyName.text = sName.uppercase(Locale.getDefault())
        lblProfileRegNo.text = "Reg No: $regNo"
        lblProfileAddress.text = if (savedCity.isNotBlank() && !address.contains(savedCity)) "$address, $savedCity" else address
        lblWorkingPeriodMetric.text = period

        if (sName.isNotBlank()) {
            lblAvatarInitial.text = sName.trim().take(1).uppercase(Locale.getDefault())
        }

        // 2. Silent background sync to keep cache fresh
        lifecycleScope.launch {
            try {
                val list = SupabaseClientManager.postgrest.from("branches")
                    .select { filter { eq("code", targetBranchCode) } }
                    .decodeList<BranchDto>()

                if (!isAdded) return@launch

                if (list.isNotEmpty()) {
                    val branch = list.first()

                    // Update AppCache with fresh branch profile data
                    val updatedBranchList = cachedBranches?.filter { !it.code.equalsIgnoreCase(targetBranchCode) }?.toMutableList() ?: mutableListOf()
                    updatedBranchList.add(branch)
                    AppCache.saveBranches(ctx, updatedBranchList)

                    val freshName = branch.name
                    val freshRegNo = branch.registrationNo ?: "REG-MH-26/X4911"
                    val freshAddress = branch.address ?: "Head Office Registry"
                    val freshPeriod = branch.committeePeriod?.ifBlank { null }
                        ?: deriveWorkingPeriodFromElectedDate(branch.committeeElectedOn)
                        ?: "2023 - 2028"

                    lblProfileSocietyName.text = freshName.uppercase(Locale.getDefault())
                    lblProfileRegNo.text = "Reg No: $freshRegNo"
                    lblProfileAddress.text = freshAddress
                    lblWorkingPeriodMetric.text = freshPeriod

                    if (freshName.isNotBlank()) {
                        lblAvatarInitial.text = freshName.trim().take(1).uppercase(Locale.getDefault())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun deriveWorkingPeriodFromElectedDate(electedOn: String?): String? {
        if (electedOn.isNullOrBlank()) return null
        return try {
            val year = electedOn.take(4).toIntOrNull()
            if (year != null && year > 2000) {
                "$year - ${year + 5}"
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun String.equalsIgnoreCase(other: String?): Boolean {
        return this.equals(other, ignoreCase = true)
    }
}