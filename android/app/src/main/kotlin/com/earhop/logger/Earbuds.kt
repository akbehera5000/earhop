package com.earhop.logger

import android.content.Context

/** Which earbud profile is active. One pair is active at a time, each with its own name filter. */
object Earbuds {
    private fun p(ctx: Context) = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)

    fun isBuds(ctx: Context) = p(ctx).getString("earbuds", "airpods") == "buds"

    fun setBuds(ctx: Context, buds: Boolean) { p(ctx).edit().putString("earbuds", if (buds) "buds" else "airpods").apply() }

    fun filter(ctx: Context): String =
        if (isBuds(ctx)) p(ctx).getString("filterBuds", "Buds2 Pro") ?: "Buds2 Pro"
        else p(ctx).getString("filter", "AirPods") ?: "AirPods"

    /** Short name used in buttons and rows. */
    fun short(ctx: Context) = if (isBuds(ctx)) "Buds" else "AirPods"

    fun label(ctx: Context) = if (isBuds(ctx)) "Galaxy Buds2 Pro" else "AirPods"
}
