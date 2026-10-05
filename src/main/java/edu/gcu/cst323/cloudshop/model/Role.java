package edu.gcu.cst323.cloudshop.model;

/**
 * The two kinds of account in the shop.
 *
 * <p>Stored as text in {@code users.role}, where a CHECK constraint limits the
 * column to these two names. Spring Security sees them as the authorities
 * {@code ROLE_BUYER} and {@code ROLE_OWNER}.
 */
public enum Role {

    /** A customer: browses the catalog, purchases products and sees their own history. */
    BUYER,

    /** The shop owner: manages inventory and reviews every sale. There is exactly one. */
    OWNER
}
