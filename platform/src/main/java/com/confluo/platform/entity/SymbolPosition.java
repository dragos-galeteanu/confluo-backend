package com.confluo.platform.entity;

/**
 * Where a currency's symbol goes relative to the amount.
 */
public enum SymbolPosition {

    /** the symbol comes before the amount, as in $12.50 */
    PREFIX,

    /** the symbol comes after the amount, as in 12,50 zł */
    SUFFIX
}
