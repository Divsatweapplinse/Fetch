package com.tonyodev.fetch2

import org.jetbrains.annotations.NotNull


/**
 * Callback interface used by Fetch to return
 * results to the caller.
 * */
@FunctionalInterface
interface Func<in T> {

    /**
     * Method called by Fetch to return requested information back to the caller.
     * @param t Results of a request made by a caller.
     * */
    fun call(@NotNull t: T)

}