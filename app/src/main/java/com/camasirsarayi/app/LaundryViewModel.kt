package com.camasirsarayi.app

import androidx.lifecycle.ViewModel
import org.json.JSONObject

internal class LaundryViewModel : ViewModel() {
    var page = "welcome"
    val back = mutableListOf<String>()
    var state = JSONObject()
    var catalog = emptyList<JSONObject>()
    var managerDorms = emptyList<JSONObject>()
    var managerEmailUnverified: Boolean? = null
    var staffStatus = ""
    var kykStaffStatus = false
    var publicDorms: List<org.json.JSONObject>? = null
    var dormSearch = ""
    var pendingInvite = ""
    var kykInvitations = emptyList<org.json.JSONObject>()
    var staffReviewDorm = ""
    var staffApplications = emptyList<org.json.JSONObject>()
    var dorm: JSONObject? = null
    var machineId = ""
    var roomId = ""
    var filter = ""
    var inboxFilter = 0
    var inboxLimit = 20
    var name = ""
    var email = ""
    var busy = false
    var adminSignIn = false
    var appleDeletion = false
    var editing = false
    var offline = false
    var receivedAt = 0L
    var lastUpdate = 0L
}
