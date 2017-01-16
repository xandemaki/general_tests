package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.PurchaseOrderContract
import com.example.contract.PurchaseOrderState
import com.example.model.PurchaseOrder
import net.corda.core.contracts.Command
import net.corda.core.contracts.TransactionType
import net.corda.core.crypto.Party
import net.corda.core.crypto.signWithECDSA
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.linearHeadsOfType
import net.corda.core.seconds
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.flows.NotaryFlow

object AmendFlow {
    class Amendment(val po: PurchaseOrder,
                    val otherParty: Party) : FlowLogic<AmendFlowResult>() {
        companion object {

            object RETRIEVING_PURCHASE_ORDER : ProgressTracker.Step("Retrieving purchase order.")
            object SIGNING : ProgressTracker.Step("Signing proposed transaction with our private key.")
            object SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE : ProgressTracker.Step("Sending partially signed transaction to seller and wait for a response.")
            object NOTARYSING : ProgressTracker.Step("Notarysing transaction.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying signatures and contract constraints.")
            object RECORDING : ProgressTracker.Step("Recording transaction in vault.")
            object SENDING_FINAL_TRANSACTION : ProgressTracker.Step("Sending fully signed transaction to seller.")

            fun tracker() = ProgressTracker(
                    RETRIEVING_PURCHASE_ORDER,
                    SIGNING,
                    SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE,
                    NOTARYSING,
                    VERIFYING_TRANSACTION,
                    RECORDING,
                    SENDING_FINAL_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): AmendFlowResult {

            try {

                val myKeyPair = serviceHub.legalIdentityKey
                val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

                progressTracker.currentStep = RETRIEVING_PURCHASE_ORDER
                val idx = serviceHub.vaultService.linearHeadsOfType<PurchaseOrderState>().values.indexOfFirst { it.state.data.purchaseOrder.orderNumber == po.orderNumber }
                if (idx < 0)
                    return AmendFlowResult.Success("Order not found!")

                val oldState = serviceHub.vaultService.linearHeadsOfType<PurchaseOrderState>().values.elementAt(idx)
                val newState = oldState.state.data.copy(purchaseOrder = po)

                val txBuilder = TransactionType.General.Builder(notary)
                        .withItems(
                                oldState,
                                newState,
                                Command(
                                        PurchaseOrderContract.Commands.Amend(),
                                        listOf(serviceHub.myInfo.legalIdentity.owningKey, otherParty.owningKey) // Both signs...
                                )
                        )

                val currentTime = serviceHub.clock.instant()
                txBuilder.setTime(currentTime, 30.seconds)

                progressTracker.currentStep = SIGNING
                val sigTx = txBuilder.signWith(myKeyPair).toSignedTransaction(checkSufficientSignatures = false)

                progressTracker.currentStep = SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE
                val stx = sendAndReceive<SignedTransaction>(otherParty, sigTx).unwrap { it }

                progressTracker.currentStep = VERIFYING_TRANSACTION
                val wtx: WireTransaction = stx.verifySignatures(notary.owningKey)
                wtx.toLedgerTransaction(serviceHub).verify() // Invoke clauses... Is it necessary? Notary already checks...

                progressTracker.currentStep = NOTARYSING
                val notarySignature = subFlow(NotaryFlow.Client(stx))
                val notTx = stx + notarySignature

                progressTracker.currentStep = RECORDING
                serviceHub.recordTransactions(listOf(notTx))

                progressTracker.currentStep = SENDING_FINAL_TRANSACTION
                send(otherParty, notTx)

                return AmendFlowResult.Success("Transaction id ${notTx.id} committed to ledger.")
            } catch(ex: Exception) {
                return AmendFlowResult.Failure(ex.message)
            }
        }
    }

    class Receive(val otherParty: Party) : FlowLogic<AmendFlowResult>() {
        companion object {
            object RECEIVED_AMENDMENT : ProgressTracker.Step("Amendment transaction received.")
            object SIGNING : ProgressTracker.Step("Signing proposed transaction with our private key.")
            object SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE : ProgressTracker.Step("Sending signed transaction to buyer and wait for a response.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object RECORDING : ProgressTracker.Step("Recording transaction in vault.")

            fun tracker() = ProgressTracker(
                    RECEIVED_AMENDMENT,
                    SIGNING,
                    SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE,
                    VERIFYING_TRANSACTION,
                    RECORDING
            )
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): AmendFlowResult {
            try {

                val ptx = receive<SignedTransaction>(otherParty).unwrap { it }
                progressTracker.currentStep = RECEIVED_AMENDMENT

                progressTracker.currentStep = SIGNING
                val mySign = serviceHub.legalIdentityKey.signWithECDSA(ptx.id.bytes)
                val vtx = ptx + mySign

                progressTracker.currentStep = SEND_TRANSACTION_AND_WAIT_FOR_RESPONSE
                val stx = sendAndReceive<SignedTransaction>(otherParty, vtx).unwrap { it }

                progressTracker.currentStep = VERIFYING_TRANSACTION
                val wtx: WireTransaction = stx.verifySignatures()
                wtx.toLedgerTransaction(serviceHub).verify()

                progressTracker.currentStep = RECORDING
                serviceHub.recordTransactions(listOf(stx))

                return AmendFlowResult.Success("Transaction id ${ptx.id} committed to ledger.")
            } catch (ex: Exception) {
                return AmendFlowResult.Failure(ex.message)
            }
        }
    }
}

/**
 * Helper class for returning a result from the flows.
 */
sealed class AmendFlowResult {
    class Success(val message: String?) : AmendFlowResult() {
        override fun toString(): String = "Success($message)"
    }

    class Failure(val message: String?) : AmendFlowResult() {
        override fun toString(): String = "Failure($message)"
    }
}