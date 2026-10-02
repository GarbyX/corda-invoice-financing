package com.garby.contracts

import com.garby.contracts.states.InvoiceStatus
import com.garby.contracts.states.InvoiceState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.CommandWithParties
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction

class InvoiceContract : Contract {

    companion object {
        const val ID = "com.garby.contracts.InvoiceContract"
    }

    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Accept : TypeOnlyCommandData(), Commands
        class Finance : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        when (command.value) {
            is Commands.Issue -> verifyIssue(tx, command)
            is Commands.Accept -> verifyAccept(tx, command)
            is Commands.Finance -> verifyFinance(tx, command)
            is Commands.Settle -> verifySettle(tx, command)
            else -> throw IllegalArgumentException("Unrecognised command: ${command.value}")
        }
    }

    // ISSUE: supplier creates a new invoice. The flow must set a time-window.
    private fun verifyIssue(tx: LedgerTransaction, command: CommandWithParties<Commands>) {
        requireThat {
            "No inputs may be consumed when issuing an invoice." using tx.inputs.isEmpty()
            "Exactly one output is required." using (tx.outputs.size == 1)
        }
        val out = tx.outputsOfType<InvoiceState>().single()
        val txTime = tx.timeWindow?.untilTime

        requireThat {
            "The invoice status must be ISSUED." using (out.status == InvoiceStatus.ISSUED)
            "Supplier and buyer must be different parties." using (out.supplier != out.buyer)
            "Face value must be positive." using (out.faceValue.quantity > 0)
            "A time-window is required on issue transactions." using (txTime != null)
            "Due date must be in the future." using (txTime != null && out.dueDate.isAfter(txTime))
            "No financier or advance may be set at issuance." using
                    (out.financier == null && out.advanceAmount == null)
        }
        requireSigners(command, out.supplier)
    }

    // ACCEPT: buyer acknowledges the invoice. Only the status changes.
    private fun verifyAccept(tx: LedgerTransaction, command: CommandWithParties<Commands>) {
        val (input, output) = transition(tx)
        requireThat {
            "The input invoice must be ISSUED." using (input.status == InvoiceStatus.ISSUED)
            "The output invoice must be ACCEPTED." using (output.status == InvoiceStatus.ACCEPTED)
            "Only the status may change on acceptance." using
                    (output == input.copy(status = InvoiceStatus.ACCEPTED))
        }
        requireSigners(command, input.buyer)
    }

    // FINANCE: a financier advances funds against an accepted invoice.
    private fun verifyFinance(tx: LedgerTransaction, command: CommandWithParties<Commands>) {
        val (input, output) = transition(tx)
        val financier = output.financier
            ?: throw IllegalArgumentException("A financier must be set when financing.")
        val advance = output.advanceAmount
            ?: throw IllegalArgumentException("An advance amount must be set when financing.")

        requireThat {
            "The input invoice must be ACCEPTED." using (input.status == InvoiceStatus.ACCEPTED)
            "The output invoice must be FINANCED." using (output.status == InvoiceStatus.FINANCED)
            "Only status, financier and advance may change." using
                    (output == input.copy(
                        status = InvoiceStatus.FINANCED,
                        financier = financier,
                        advanceAmount = advance
                    ))
            "The advance must be in the invoice currency." using (advance.token == input.faceValue.token)
            "The advance must be positive." using (advance.quantity > 0)
            "The advance cannot exceed the face value." using (advance <= input.faceValue)
            "The financier must be independent of supplier and buyer." using
                    (financier != input.supplier && financier != input.buyer)
        }
        // The supplier consents to assigning the receivable; the financier consents to the advance.
        requireSigners(command, input.supplier, financier)
    }

    // SETTLE: buyer pays at maturity and the financier confirms. SETTLED is terminal.
    private fun verifySettle(tx: LedgerTransaction, command: CommandWithParties<Commands>) {
        val (input, output) = transition(tx)
        val financier = input.financier
            ?: throw IllegalArgumentException("A financed invoice must have a financier.")

        requireThat {
            "The input invoice must be FINANCED." using (input.status == InvoiceStatus.FINANCED)
            "The output invoice must be SETTLED." using (output.status == InvoiceStatus.SETTLED)
            "Only the status may change on settlement." using
                    (output == input.copy(status = InvoiceStatus.SETTLED))
        }
        requireSigners(command, input.buyer, financier)
    }

    /** Shared checks for single-input, single-output transitions of the same invoice. */
    private fun transition(tx: LedgerTransaction): Pair<InvoiceState, InvoiceState> {
        requireThat {
            "Exactly one input is required." using (tx.inputs.size == 1)
            "Exactly one output is required." using (tx.outputs.size == 1)
        }
        val input = tx.inputsOfType<InvoiceState>().single()
        val output = tx.outputsOfType<InvoiceState>().single()
        requireThat {
            "The invoice linearId must not change." using (input.linearId == output.linearId)
        }
        return input to output
    }

    private fun requireSigners(command: CommandWithParties<Commands>, vararg required: Party) {
        val missing = required.filterNot { it.owningKey in command.signers }
        require(missing.isEmpty()) { "Missing required signatures from: ${missing.map { it.name }}" }
    }
}