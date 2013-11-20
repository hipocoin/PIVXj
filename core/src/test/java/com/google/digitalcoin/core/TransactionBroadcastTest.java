/**
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.digitalcoin.core;

import com.google.digitalcoin.params.UnitTestParams;
import com.google.digitalcoin.store.MemoryBlockStore;
import com.google.digitalcoin.utils.TestUtils;
import com.google.digitalcoin.utils.Threading;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class TransactionBroadcastTest extends TestWithPeerGroup {
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp(new MemoryBlockStore(UnitTestParams.get()));
        peerGroup.addWallet(wallet);
        // Fix the random permutation that TransactionBroadcast uses to shuffle the peers.
        TransactionBroadcast.random = new Random(0);
        peerGroup.setMinBroadcastConnections(2);
    }

    @Test
    public void fourPeers() throws Exception {
        FakeChannel[] channels = { connectPeer(1), connectPeer(2), connectPeer(3), connectPeer(4) };
        Transaction tx = new Transaction(params);
        TransactionBroadcast broadcast = new TransactionBroadcast(peerGroup, tx);
        ListenableFuture<Transaction> future = broadcast.broadcast();
        assertFalse(future.isDone());
        // We expect two peers to receive a tx message, and at least one of the others must announce for the future to
        // complete successfully.
        Message[] messages = {
                (Message) outbound(channels[0]),
                (Message) outbound(channels[1]),
                (Message) outbound(channels[2]),
                (Message) outbound(channels[3])
        };
        // 0 and 3 are randomly selected to receive the broadcast.
        assertEquals(tx, messages[0]);
        assertEquals(tx, messages[3]);
        assertNull(messages[1]);
        assertNull(messages[2]);
        Threading.waitForUserCode();
        assertFalse(future.isDone());
        inbound(channels[1], InventoryMessage.with(tx));
        Threading.waitForUserCode();
        assertTrue(future.isDone());
    }

    @Test
    public void peerGroupWalletIntegration() throws Exception {
        // Make sure we can create spends, and that they are announced. Then do the same with offline mode.

        // Set up connections and block chain.
        FakeChannel p1 = connectPeer(1, new VersionMessage(params, 2));
        FakeChannel p2 = connectPeer(2);

        // Send ourselves a bit of money.
        Block b1 = TestUtils.makeSolvedTestBlock(blockStore, address);
        inbound(p1, b1);
        assertNull(outbound(p1));

        assertEquals(Utils.toNanoCoins(50, 0), wallet.getBalance());

        // Check that the wallet informs us of changes in confidence as the transaction ripples across the network.
        final Transaction[] transactions = new Transaction[1];
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                transactions[0] = tx;
            }
        });

        // Now create a spend, and expect the announcement on p1.
        Address dest = new ECKey().toAddress(params);
        Wallet.SendResult sendResult = wallet.sendCoins(peerGroup, dest, Utils.toNanoCoins(1, 0));
        assertNotNull(sendResult.tx);
        Threading.waitForUserCode();
        assertFalse(sendResult.broadcastComplete.isDone());
        assertEquals(transactions[0], sendResult.tx);
        assertEquals(transactions[0].getConfidence().numBroadcastPeers(), 0);
        transactions[0] = null;
        Transaction t1 = (Transaction) outbound(p1);
        assertNotNull(t1);
        // 49 BTC in change.
        assertEquals(Utils.toNanoCoins(49, 0), t1.getValueSentToMe(wallet));
        // The future won't complete until it's heard back from the network on p2.
        InventoryMessage inv = new InventoryMessage(params);
        inv.addTransaction(t1);
        inbound(p2, inv);
        Threading.waitForUserCode();
        assertTrue(sendResult.broadcastComplete.isDone());
        assertEquals(transactions[0], sendResult.tx);
        assertEquals(1, transactions[0].getConfidence().numBroadcastPeers());
        // Confirm it.
        Block b2 = TestUtils.createFakeBlock(blockStore, t1).block;
        inbound(p1, b2);
        assertNull(outbound(p1));

        // Do the same thing with an offline transaction.
        peerGroup.removeWallet(wallet);
        Wallet.SendRequest req = Wallet.SendRequest.to(dest, Utils.toNanoCoins(2, 0));
        req.ensureMinRequiredFee = false;
        Transaction t3 = checkNotNull(wallet.sendCoinsOffline(req));
        assertNull(outbound(p1));  // Nothing sent.
        // Add the wallet to the peer group (simulate initialization). Transactions should be announced.
        peerGroup.addWallet(wallet);
        // Transaction announced to the first peer.
        assertEquals(t3.getHash(), ((Transaction) outbound(p1)).getHash());
    }
}
