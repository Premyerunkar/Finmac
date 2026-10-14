package com.sierratechnologies.finmac

object Util {
    const val SUPABASE_URL = "https://wjwvikeulvxnrzzjtezt.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Indqd3Zpa2V1bHZ4bnJ6emp0ZXp0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTExNjY2MjksImV4cCI6MjEwNjc0MjYyOX0.kcbpeJ40h8-rOdI3GeTJgARiXc_77DwlzPR1XACTtWE"

    object Session {
        var isSuperAdmin: Boolean = false
        var activeBranchCode: String = ""
        var activeBranchName: String = ""
        var activeUsername: String = ""
    }
}