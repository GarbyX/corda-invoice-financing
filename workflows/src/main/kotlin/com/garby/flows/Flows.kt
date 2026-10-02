package com.garby.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.*
import net.corda.core.utilities.ProgressTracker
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.contracts.Amount
import net.corda.core.contracts.requireThat
import com.garby.contracts.InvoiceContract
import com.garby.contracts.states.InvoiceState
import com.garby.contracts.states.InvoiceStatus
import java.time.Instant
import java.util.Currency

@InitiatingFlow
@StartableByRPC
class Initiator(
    private val buyer: Party,
    private val faceValue: Amount<Currency>,
    private val dueDate: Instant
) : FlowLogic<SignedTransaction>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val supplier = ourIdentity

        // 1. Get a reference to the notary service on our network
        val notary = serviceHub.networkMapCache.getNotary(CordaX500Name.parse("O=Notary,L=London,C=GB"))
            ?: throw FlowException("Notary not found.")

        // 2. Compose the State matching your InvoiceState definition
        val output = InvoiceState(
            supplier = supplier,
            buyer = buyer,
            faceValue = faceValue,
            dueDate = dueDate,
            status = InvoiceStatus.ISSUED
        )

        // 3. Create a new TransactionBuilder and assign the correct "Issue" command
        val builder = TransactionBuilder(notary)
            .addCommand(InvoiceContract.Commands.Issue(), listOf(supplier.owningKey, buyer.owningKey))
            .addOutputState(output)

        // Set a time-window because InvoiceContract requires it for the Issue command
        builder.setTimeWindow(net.corda.core.contracts.TimeWindow.untilOnly(dueDate))
        // 4. Verify and sign it with our KeyPair
        builder.verify(serviceHub)
        val ptx = serviceHub.signInitialTransaction(builder)

        // 5. Gather counterparty sessions using native Kotlin lists instead of Java streams
        // This solves the 'toList()' compilation type-inference failure completely
        val otherParties = output.participants
            .filterIsInstance<Party>()
            .filter { it != ourIdentity }

        val sessions = otherParties.map { initiateFlow(it) }

        // 6. Collect the other party's signature
        val stx = subFlow(CollectSignaturesFlow(ptx, sessions))

        // 7. Finalise the transaction
        return subFlow(FinalityFlow(stx, sessions))
    }
}

@InitiatedBy(Initiator::class)
class Responder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                // Additional custom checks can go here
            }
        }
        val txId = subFlow(signTransactionFlow).id
        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}
