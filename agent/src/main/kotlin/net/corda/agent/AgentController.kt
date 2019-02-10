package net.corda.agent

import net.corda.accounts.flows.GetAccountsFlow
import net.corda.accounts.states.AccountInfo
import net.corda.core.contracts.StateAndRef
import net.corda.core.utilities.toBase58String
import net.corda.gold.trading.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
class AgentController(@Autowired private val rpcConnection: NodeRPCConnection, @Autowired private val agentAccountProvider: AgentAccountProvider) {

    @RequestMapping("/loans", method = [RequestMethod.GET])
    fun getLoans(): List<LoanBookView> {
        return getAllLoans().map { it.toLoanBookView() }
    }

    private fun getAllLoans() = rpcConnection.proxy.startFlowDynamic(GetAllLoansOwnedByAccountFlow::class.java, agentAccountProvider.agentAccount).returnValue.get()

    @RequestMapping("/createLoan", method = [RequestMethod.GET])
    fun createLoan(): LoanBookView {
        return rpcConnection.proxy.startFlowDynamic(IssueLoanBookFlow::class.java, 10_000_000L, agentAccountProvider.agentAccount).returnValue.get()
            .let { it.toLoanBookView() }
    }

    @RequestMapping("/accounts", method = [RequestMethod.GET])
    fun accountsKnown(): List<AccountInfoView> {
        return getAllAccounts().map { it.toAccountView() }
    }

    private fun getAllAccounts() = rpcConnection.proxy.startFlowDynamic(GetAccountsFlow::class.java, false).returnValue.get()

    @RequestMapping("/loan/split/{txHash}/{txIdx}", method = [RequestMethod.GET])
    fun splitLoan(@PathVariable("txHash") txHash: String, @PathVariable("txIdx") txIdx: Int): List<LoanBookView> {
        val loanToSplit = getAllLoans().filter { it.ref.txhash.toString() == txHash }.filter { it.ref.index == txIdx }.single()
        val resultOfSplit = rpcConnection.proxy.startFlowDynamic(SplitLoanFlow::class.java, loanToSplit, loanToSplit.state.data.valueInUSD / 2).returnValue.get()
        return getAllLoans().map { it.toLoanBookView() }
    }

    @RequestMapping("/loan/move/{txHash}/{txIdx}/{accountKey}", method = [RequestMethod.GET])
    fun moveLoan(@PathVariable("txHash") txHash: String, @PathVariable("txIdx") txIdx: Int, @PathVariable("accountKey") accountKey: String): List<LoanBookView> {
        val loanToMove = getAllLoans().filter { it.ref.txhash.toString() == txHash }.single { it.ref.index == txIdx }
        val accountToMoveInto = getAllAccounts().single { it.state.data.signingKey.toBase58String() == accountKey }
        val resultOfMove = rpcConnection.proxy.startFlowDynamic(MoveLoanBookToNewAccount::class.java, accountToMoveInto.state.data.accountId, loanToMove).returnValue.get()
        return getAllLoans().map { it.toLoanBookView() }
    }



    data class AccountInfoView(
        val accountName: String,
        val accountHost: String,
        val accountId: UUID,
        val key: String?,
        val carbonCopyReivers: List<String> = listOf()
    )

    data class LoanBookView(val dealId: UUID, val valueInUSD: Long, val owningAccount: String? = null, val index: Int, val txHash: String)

}

private fun StateAndRef<AccountInfo>.toAccountView(): AgentController.AccountInfoView {
    val data = this.state.data
    return AgentController.AccountInfoView(
        data.accountName,
        data.accountHost.name.toString(),
        data.accountId,
        data.signingKey.toBase58String(),
        data.carbonCopyReivers.map { it.name.toString() })
}

private fun StateAndRef<LoanBook>.toLoanBookView(): AgentController.LoanBookView {
    val data = this.state.data
    return AgentController.LoanBookView(data.dealId, data.valueInUSD, data.owningAccount?.toBase58String(), this.ref.index, this.ref.txhash.toString())
}