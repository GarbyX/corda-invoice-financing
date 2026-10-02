package com.garby.contracts.states

import com.garby.contracts.InvoiceContract
import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.time.Instant
import java.util.Currency

/** Lifecycle of an invoice: ISSUED -> ACCEPTED -> FINANCED -> SETTLED. */
@CordaSerializable
enum class InvoiceStatus { ISSUED, ACCEPTED, FINANCED, SETTLED }

/**
 * A single invoice moving through its lifecycle on the ledger.
 *
 * The financier is null until financing happens, so it only becomes a
 * participant (and only sees the invoice) from the FINANCED step onwards.
 */
@BelongsToContract(InvoiceContract::class)
data class InvoiceState(
    val supplier: Party,
    val buyer: Party,
    val faceValue: Amount<Currency>,
    val dueDate: Instant,
    val status: InvoiceStatus = InvoiceStatus.ISSUED,
    val financier: Party? = null,
    val advanceAmount: Amount<Currency>? = null,
    override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState {

    override val participants: List<AbstractParty>
        get() = listOfNotNull(supplier, buyer, financier)
}