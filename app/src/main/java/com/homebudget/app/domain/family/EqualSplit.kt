package com.homebudget.app.domain.family

/** Splits minor units evenly; any remainder is assigned to the first members. */
fun equalShares(totalMinor: Long, memberCount: Int): List<Long> {
    require(totalMinor >= 0) { "Total must not be negative" }
    if (memberCount <= 0) return emptyList()
    val base = totalMinor / memberCount
    val remainder = totalMinor % memberCount
    return List(memberCount) { index -> base + if (index < remainder) 1 else 0 }
}
