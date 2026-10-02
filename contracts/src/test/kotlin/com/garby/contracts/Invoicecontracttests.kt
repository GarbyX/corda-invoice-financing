package com.garby.contracts

import com.garby.contracts.states.InvoiceState
import com.garby.contracts.states.InvoiceStatus
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TimeWindow
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Currency

class InvoiceContractTests {

    private val ledgerServices = MockServices(listOf("com.garby"))

    private val sme = TestIdentity(CordaX500Name("SME", "Lagos", "NG"))
    private val buyer = TestIdentity(CordaX500Name("Buyer", "Abuja", "NG"))
    private val financier = TestIdentity(CordaX500Name("Financier", "Lagos", "NG"))

    private val ngn: Currency = Currency.getInstance("NGN")
    private val usd: Currency = Currency.getInstance("USD")
    private val now: Instant = Instant.parse("2026-10-02T10:00:00Z")
    private val invoiceId = UniqueIdentifier()

    private fun naira(units: Long) = Amount(units * 100, ngn)

    // Lifecycle fixtures. All share one linearId so transitions look like the same invoice.
    private val issued = InvoiceState(
        supplier = sme.party,
        buyer = buyer.party,
        faceValue = naira(1_000_000),
        dueDate = now.plus(Duration.ofDays(30)),
        linearId = invoiceId
    )
    private val accepted = issued.copy(status = InvoiceStatus.ACCEPTED)
    private val financed = accepted.copy(
        status = InvoiceStatus.FINANCED,
        financier = financier.party,
        advanceAmount = naira(850_000)
    )
    private val settled = financed.copy(status = InvoiceStatus.SETTLED)

    // ---------------------------------------------------------------- ISSUE

    @Test
    fun `issue succeeds with supplier signature and time-window`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued)
                timeWindow(TimeWindow.untilOnly(now))
                command(sme.publicKey, InvoiceContract.Commands.Issue())
                verifies()
            }
        }
    }

    @Test
    fun `issue fails without a time-window`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued)
                command(sme.publicKey, InvoiceContract.Commands.Issue())
                `fails with`("A time-window is required")
            }
        }
    }

    @Test
    fun `issue fails when due date is in the past`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued.copy(dueDate = now.minus(Duration.ofDays(1))))
                timeWindow(TimeWindow.untilOnly(now))
                command(sme.publicKey, InvoiceContract.Commands.Issue())
                `fails with`("Due date must be in the future")
            }
        }
    }

    @Test
    fun `issue fails when supplier and buyer are the same party`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued.copy(buyer = sme.party))
                timeWindow(TimeWindow.untilOnly(now))
                command(sme.publicKey, InvoiceContract.Commands.Issue())
                `fails with`("Supplier and buyer must be different parties")
            }
        }
    }

    @Test
    fun `issue fails when face value is zero`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued.copy(faceValue = naira(0)))
                timeWindow(TimeWindow.untilOnly(now))
                command(sme.publicKey, InvoiceContract.Commands.Issue())
                `fails with`("Face value must be positive")
            }
        }
    }

    @Test
    fun `issue fails when the supplier has not signed`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, issued)
                timeWindow(TimeWindow.untilOnly(now))
                command(buyer.publicKey, InvoiceContract.Commands.Issue())
                `fails with`("Missing required signatures")
            }
        }
    }

    // --------------------------------------------------------------- ACCEPT

    @Test
    fun `accept succeeds when the buyer signs`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, issued)
                output(InvoiceContract.ID, accepted)
                command(buyer.publicKey, InvoiceContract.Commands.Accept())
                verifies()
            }
        }
    }

    @Test
    fun `accept fails when only the supplier signs`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, issued)
                output(InvoiceContract.ID, accepted)
                command(sme.publicKey, InvoiceContract.Commands.Accept())
                `fails with`("Missing required signatures")
            }
        }
    }

    @Test
    fun `accept fails when anything besides status changes`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, issued)
                output(InvoiceContract.ID, accepted.copy(faceValue = naira(2_000_000)))
                command(buyer.publicKey, InvoiceContract.Commands.Accept())
                `fails with`("Only the status may change on acceptance")
            }
        }
    }

    // -------------------------------------------------------------- FINANCE

    @Test
    fun `finance succeeds with supplier and financier signatures`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, financed)
                command(listOf(sme.publicKey, financier.publicKey), InvoiceContract.Commands.Finance())
                verifies()
            }
        }
    }

    @Test
    fun `finance fails when invoice was never accepted`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, issued)
                output(InvoiceContract.ID, financed)
                command(listOf(sme.publicKey, financier.publicKey), InvoiceContract.Commands.Finance())
                `fails with`("The input invoice must be ACCEPTED")
            }
        }
    }

    @Test
    fun `finance fails when advance exceeds face value`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, financed.copy(advanceAmount = naira(1_100_000)))
                command(listOf(sme.publicKey, financier.publicKey), InvoiceContract.Commands.Finance())
                `fails with`("The advance cannot exceed the face value")
            }
        }
    }

    @Test
    fun `finance fails when advance is in a different currency`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, financed.copy(advanceAmount = Amount(100_000, usd)))
                command(listOf(sme.publicKey, financier.publicKey), InvoiceContract.Commands.Finance())
                `fails with`("The advance must be in the invoice currency")
            }
        }
    }

    @Test
    fun `finance fails when the financier is the buyer`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, financed.copy(financier = buyer.party))
                command(listOf(sme.publicKey, buyer.publicKey), InvoiceContract.Commands.Finance())
                `fails with`("The financier must be independent")
            }
        }
    }

    @Test
    fun `finance fails when the financier has not signed`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, financed)
                command(sme.publicKey, InvoiceContract.Commands.Finance())
                `fails with`("Missing required signatures")
            }
        }
    }

    // --------------------------------------------------------------- SETTLE

    @Test
    fun `settle succeeds with buyer and financier signatures`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, financed)
                output(InvoiceContract.ID, settled)
                command(listOf(buyer.publicKey, financier.publicKey), InvoiceContract.Commands.Settle())
                verifies()
            }
        }
    }

    @Test
    fun `settle fails when the financier has not signed`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, financed)
                output(InvoiceContract.ID, settled)
                command(buyer.publicKey, InvoiceContract.Commands.Settle())
                `fails with`("Missing required signatures")
            }
        }
    }

    @Test
    fun `settle fails for an invoice that was never financed`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, accepted)
                output(InvoiceContract.ID, settled)
                command(listOf(buyer.publicKey, financier.publicKey), InvoiceContract.Commands.Settle())
                `fails with`("A financed invoice must have a financier")
            }
        }
    }

    @Test
    fun `a settled invoice cannot be settled again`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, settled)
                output(InvoiceContract.ID, settled)
                command(listOf(buyer.publicKey, financier.publicKey), InvoiceContract.Commands.Settle())
                `fails with`("The input invoice must be FINANCED")
            }
        }
    }
}