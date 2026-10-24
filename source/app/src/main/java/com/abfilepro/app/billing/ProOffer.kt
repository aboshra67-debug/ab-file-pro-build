package com.abfilepro.app.billing

/** One product as Google Play describes it (price already formatted for the person's country). */
data class ProOffer(val productId: String, val price: String, val subscription: Boolean, val period: String = "")
