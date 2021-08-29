package net.corda.samples.obligation.flows;

import net.corda.core.identity.Party;
import net.corda.testing.node.*;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.CommandWithParties;
import net.corda.core.concurrent.CordaFuture;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.transactions.LedgerTransaction;
import net.corda.core.transactions.SignedTransaction;
import net.corda.finance.Currencies;
import net.corda.finance.contracts.asset.Cash;
import net.corda.samples.obligation.states.IOUState;
import net.corda.samples.obligation.contracts.IOUContract;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Practical exercise instructions Flows part 3.
 * Uncomment the unit tests and use the hints + unit test body to complete the FLows such that the unit tests pass.
 */
public class IOUSettleFlowTests {

    private MockNetwork mockNetwork;
    private StartedMockNode a, b, c;

    @Before
    public void setup() {
        MockNetworkParameters mockNetworkParameters = new MockNetworkParameters().withCordappsForAllNodes(
                Arrays.asList(
                        TestCordapp.findCordapp("net.corda.samples.obligation.contracts"),
                        TestCordapp.findCordapp("net.corda.finance.schemas")
                )
        ).withNotarySpecs(Arrays.asList(new MockNetworkNotarySpec(new CordaX500Name("Notary", "London", "GB"))));
        mockNetwork = new MockNetwork(mockNetworkParameters);
        System.out.println(mockNetwork);

        a = mockNetwork.createNode(new MockNodeParameters());
        b = mockNetwork.createNode(new MockNodeParameters());
        c = mockNetwork.createNode(new MockNodeParameters());

        ArrayList<StartedMockNode> startedNodes = new ArrayList<>();
        startedNodes.add(a);
        startedNodes.add(b);
        startedNodes.add(c);

        // For real nodes this happens automatically, but we have to manually register the flows for tests
        startedNodes.forEach(el -> el.registerInitiatedFlow(IOUSettleFlow.Responder.class));
        startedNodes.forEach(el -> el.registerInitiatedFlow(IOUIssueFlow.ResponderFlow.class));
        mockNetwork.runNetwork();
    }

    @After
    public void tearDown() {
        mockNetwork.stopNodes();
    }

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private SignedTransaction issueIOU(IOUState iouState) throws InterruptedException, ExecutionException {
        IOUIssueFlow.InitiatorFlow flow = new IOUIssueFlow.InitiatorFlow(iouState);
        CordaFuture future = a.startFlow(flow);
        mockNetwork.runNetwork();
        return (SignedTransaction) future.get();
    }


    /**
     * Task 1.
     * The first task is to grab the [IOUState] for the given [linearId] from the vault, assemble a transaction
     * and sign it.
     * TODO: Grab the IOU for the given [linearId] from the vault, build and sign the settle transaction.
     * Hints:
     * - Use the code from the [IOUTransferFlow] to get the correct [IOUState] from the vault.
     * - You will need to use the [Cash.generateSpend] functionality of the vault to add the cash states and cash command
     *   to your transaction. The API is quite simple. It takes a reference to a [ServiceHub], [TransactionBuilder], an [Amount],
     *   our Identity as a [PartyAndCertificate], the [Party] object for the recipient, and a set of the spending parties.
     *   The function will mutate your builder by adding the states and commands.
     * - You then need to produce the output [IOUState] by using the [IOUState.pay] function.
     * - Add the input [IOUState] [StateAndRef] and the new output [IOUState] to the transaction.
     * - Sign the transaction and return it.
     */
    @Test
    public void flowReturnsCorrectlyFormedPartiallySignedTransaction() throws Exception {
        SignedTransaction stx = issueIOU(new IOUState(Currencies.POUNDS(10), b.getInfo().getLegalIdentities().get(0), a.getInfo().getLegalIdentities().get(0)));
        IOUState inputIOU = stx.getTx().outputsOfType(IOUState.class).get(0);
        IOUSettleFlow.InitiatorFlow flow = new IOUSettleFlow.InitiatorFlow(inputIOU.getLinearId(), Currencies.POUNDS(5));
        Future<SignedTransaction> futureSettleResult = a.startFlow(flow);

        mockNetwork.runNetwork();

        SignedTransaction settleResult = futureSettleResult.get();
        // Check the transaction is well formed...
        // One output IOUState, one input IOUState reference, input and output cash
        a.transaction(() -> {
            try {
                LedgerTransaction ledgerTx = settleResult.toLedgerTransaction(a.getServices(), false);
                assert(ledgerTx.getInputs().size() == 1);
                assert(ledgerTx.getOutputs().size() == 1);

                IOUState outputIOU = ledgerTx.outputsOfType(IOUState.class).get(0);
                IOUState correctOutputIOU = inputIOU.pay(Currencies.POUNDS(5));

                assert (outputIOU.getAmount().equals(correctOutputIOU.getAmount()));
                assert (outputIOU.getPaid().equals(correctOutputIOU.getPaid()));
                assert (outputIOU.getLender().equals(correctOutputIOU.getLender()));
                assert (outputIOU.getBorrower().equals(correctOutputIOU.getBorrower()));

                CommandWithParties command = ledgerTx.getCommands().get(0);
                assert (command.getValue().equals(new IOUContract.Commands.Settle()));

                settleResult.verifySignaturesExcept(b.getInfo().getLegalIdentities().get(0).getOwningKey(),
                        mockNetwork.getDefaultNotaryIdentity().getOwningKey());

                return null;
            } catch (Exception exception) {
                System.out.println(exception);
            }
            return null;
        });
    }

    /**
     * Task 2.
     * Only the borrower should be running this flows for a particular IOU.
     * TODO: Grab the IOU for the given [linearId] from the vault and check the node running the flows is the borrower.
     * Hint: Use the data within the iou obtained from the vault to check the right node is running the flows.
     */
    @Test
    public void settleFlowCanOnlyBeRunByBorrower() throws Exception {
        SignedTransaction stx = issueIOU(new IOUState(Currencies.POUNDS(10),b.getInfo().getLegalIdentities().get(0), a.getInfo().getLegalIdentities().get(0)));

        IOUState inputIOU = stx.getTx().outputsOfType(IOUState.class).get(0);
        IOUSettleFlow.InitiatorFlow flow = new IOUSettleFlow.InitiatorFlow(inputIOU.getLinearId(), Currencies.POUNDS(5));
        Future<SignedTransaction> futureSettleResult = b.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            futureSettleResult.get();
        } catch (Exception exception) {
            assert exception.getMessage().equals("java.lang.IllegalArgumentException: The borrower must issue the flows");
        }
    }



    /**
     * Task 4.
     * We need to get the transaction signed by the other party.
     * TODO: Use a subFlow call to [initiateFlow] and the [SignTransactionFlow] to get a signature from the lender.
     */
    @Test
    public void flowReturnsTransactionSignedByBothParties() throws Exception {
        SignedTransaction stx = issueIOU(new IOUState(Currencies.POUNDS(10), b.getInfo().getLegalIdentities().get(0), a.getInfo().getLegalIdentities().get(0)));
        IOUState inputIOU = stx.getTx().outputsOfType(IOUState.class).get(0);
        IOUSettleFlow.InitiatorFlow flow = new IOUSettleFlow.InitiatorFlow(inputIOU.getLinearId(), Currencies.POUNDS(5));
        Future<SignedTransaction> futureSettleResult = a.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            futureSettleResult.get().verifySignaturesExcept(mockNetwork.getDefaultNotaryIdentity().getOwningKey());
        } catch (Exception exception) {
            assert exception.getMessage().equals("java.lang.IllegalArgumentException: Borrower has no GBP to settle.");
        }
    }

    /**
     * Task 5.
     * We need to get the transaction signed by the notary service
     * TODO: Use a subFlow call to the [FinalityFlow] to get a signature from the lender.
     */
    @Test
    public void flowReturnsCommittedTransaction() throws Exception {
        SignedTransaction stx = issueIOU(new IOUState(Currencies.POUNDS(10), b.getInfo().getLegalIdentities().get(0), a.getInfo().getLegalIdentities().get(0)));
        IOUState inputIOU = stx.getTx().outputsOfType(IOUState.class).get(0);
        IOUSettleFlow.InitiatorFlow flow = new IOUSettleFlow.InitiatorFlow(inputIOU.getLinearId(), Currencies.POUNDS(5));
        Future<SignedTransaction> futureSettleResult = a.startFlow(flow);

        try {
            mockNetwork.runNetwork();
            futureSettleResult.get().verifyRequiredSignatures();
        } catch (Exception exception) {
            assert exception.getMessage().equals("java.lang.IllegalArgumentException: Borrower has no GBP to settle.");
        }
    }

}
