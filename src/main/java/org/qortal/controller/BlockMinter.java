package org.qortal.controller;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.Block.ValidationResult;
import org.qortal.block.BlockChain;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.BlockRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

// Minting new blocks

public class BlockMinter extends Thread {

	// Properties
	private boolean running;

	// Other properties
	private static final Logger LOGGER = LogManager.getLogger(BlockMinter.class);
	private static Long lastLogTimestamp;
	private static Long logTimeout;

	// Recovery
	public static final long INVALID_BLOCK_RECOVERY_TIMEOUT = 10 * 60 * 1000L; // ms

	// Constructors

	public BlockMinter() {
		this.running = true;
	}

	// Main thread loop
	@Override
	public void run() {
		Thread.currentThread().setName("BlockMinter");

		if (Settings.getInstance().isLite()) {
			// Lite nodes do not mint
			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			if (Settings.getInstance().getWipeUnconfirmedOnStart()) {
				// Wipe existing unconfirmed transactions
				List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();

				for (TransactionData transactionData : unconfirmedTransactions) {
					LOGGER.trace(() -> String.format("Deleting unconfirmed transaction %s", Base58.encode(transactionData.getSignature())));
					repository.getTransactionRepository().delete(transactionData);
				}

				repository.saveChanges();
			}

			// Going to need this a lot...
			BlockRepository blockRepository = repository.getBlockRepository();
			BlockData previousBlockData = null;

			// Vars to keep track of blocks that were skipped due to chain weight
			byte[] parentSignatureForLastLowWeightBlock = null;
			Long timeOfLastLowWeightBlock = null;

			List<Block> newBlocks = new ArrayList<>();

			// Flags for tracking change in whether minting is possible,
			// so we can notify Controller, and further update SysTray, etc.
			boolean isMintingPossible = false;
			boolean wasMintingPossible = isMintingPossible;
			while (running) {
				repository.discardChanges(); // Free repository locks, if any

				if (isMintingPossible != wasMintingPossible)
					Controller.getInstance().onMintingPossibleChange(isMintingPossible);

				wasMintingPossible = isMintingPossible;

				// Sleep for a while
				Thread.sleep(1000);

				isMintingPossible = false;

				final Long now = NTP.getTime();
				if (now == null)
					continue;

				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					continue;

				// No online accounts? (e.g. during startup)
				if (OnlineAccountsManager.getInstance().getOnlineAccounts().isEmpty())
					continue;

				List<MintingAccountData> mintingAccountsData = repository.getAccountRepository().getMintingAccounts();
				// No minting accounts?
				if (mintingAccountsData.isEmpty())
					continue;

				// Disregard minting accounts that are no longer valid, e.g. by transfer/loss of founder flag or account level
				// Note that minting accounts are actually reward-shares in Qortal
				Iterator<MintingAccountData> madi = mintingAccountsData.iterator();
				while (madi.hasNext()) {
					MintingAccountData mintingAccountData = madi.next();

					RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccountData.getPublicKey());
					if (rewardShareData == null) {
						// Reward-share doesn't exist - probably cancelled but not yet removed from node's list of minting accounts
						madi.remove();
						continue;
					}

					Account mintingAccount = new Account(repository, rewardShareData.getMinter());
					if (!mintingAccount.canMint()) {
						// Minting-account component of reward-share can no longer mint - disregard
						madi.remove();
						continue;
					}

					// Optional (non-validated) prevention of block submissions below a defined level.
					// This is an unvalidated version of Blockchain.minAccountLevelToMint
					// and exists only to reduce block candidates by default.
					int level = mintingAccount.getEffectiveMintingLevel();
					if (level < BlockChain.getInstance().getMinAccountLevelForBlockSubmissions()) {
						madi.remove();
						continue;
					}
				}

				// Needs a mutable copy of the unmodifiableList
				List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());
				BlockData lastBlockData = blockRepository.getLastBlock();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Disregard peers that don't have a recent block, but only if we're not in recovery mode.
				// In that mode, we want to allow minting on top of older blocks, to recover stalled networks.
				if (Synchronizer.getInstance().getRecoveryMode() == false)
					peers.removeIf(Controller.hasNoRecentBlock);

				// Don't mint if we don't have enough up-to-date peers as where would the transactions/consensus come from?
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers())
					continue;

				// If we are stuck on an invalid block, we should allow an alternative to be minted
				boolean recoverInvalidBlock = false;
				if (Synchronizer.getInstance().timeInvalidBlockLastReceived != null) {
					// We've had at least one invalid block
					long timeSinceLastValidBlock = NTP.getTime() - Synchronizer.getInstance().timeValidBlockLastReceived;
					long timeSinceLastInvalidBlock = NTP.getTime() - Synchronizer.getInstance().timeInvalidBlockLastReceived;
					if (timeSinceLastValidBlock > INVALID_BLOCK_RECOVERY_TIMEOUT) {
						if (timeSinceLastInvalidBlock < INVALID_BLOCK_RECOVERY_TIMEOUT) {
							// Last valid block was more than 10 mins ago, but we've had an invalid block since then
							// Assume that the chain has stalled because there is no alternative valid candidate
							// Enter recovery mode to allow alternative, valid candidates to be minted
							recoverInvalidBlock = true;
						}
					}
				}

				// If our latest block isn't recent then we need to synchronize instead of minting, unless we're in recovery mode.
				if (!peers.isEmpty() && lastBlockData.getTimestamp() < minLatestBlockTimestamp)
					if (Synchronizer.getInstance().getRecoveryMode() == false && recoverInvalidBlock == false)
						continue;

				// There are enough peers with a recent block and our latest block is recent
				// so go ahead and mint a block if possible.
				isMintingPossible = true;

				// Check blockchain hasn't changed
				if (previousBlockData == null || !Arrays.equals(previousBlockData.getSignature(), lastBlockData.getSignature())) {
					previousBlockData = lastBlockData;
					newBlocks.clear();

					// Reduce log timeout
					logTimeout = 10 * 1000L;

					// Last low weight block is no longer valid
					parentSignatureForLastLowWeightBlock = null;
				}

				// Discard accounts we have already built blocks with
				mintingAccountsData.removeIf(mintingAccountData -> newBlocks.stream().anyMatch(newBlock -> Arrays.equals(newBlock.getBlockData().getMinterPublicKey(), mintingAccountData.getPublicKey())));

				// Do we need to build any potential new blocks?
				List<PrivateKeyAccount> newBlocksMintingAccounts = mintingAccountsData.stream().map(accountData -> new PrivateKeyAccount(repository, accountData.getPrivateKey())).collect(Collectors.toList());

				// We might need to sit the next block out, if one of our minting accounts signed the previous one
				final byte[] previousBlockMinter = previousBlockData.getMinterPublicKey();
				final boolean mintedLastBlock = mintingAccountsData.stream().anyMatch(mintingAccount -> Arrays.equals(mintingAccount.getPublicKey(), previousBlockMinter));
				if (mintedLastBlock) {
					LOGGER.trace(String.format("One of our keys signed the last block, so we won't sign the next one"));
					continue;
				}

				if (parentSignatureForLastLowWeightBlock != null) {
					// The last iteration found a higher weight block in the network, so sleep for a while
					// to allow is to sync the higher weight chain. We are sleeping here rather than when
					// detected as we don't want to hold the blockchain lock open.
					LOGGER.debug("Sleeping for 10 seconds...");
					Thread.sleep(10 * 1000L);
				}

				for (PrivateKeyAccount mintingAccount : newBlocksMintingAccounts) {
					// First block does the AT heavy-lifting
					if (newBlocks.isEmpty()) {
						Block newBlock = Block.mint(repository, previousBlockData, mintingAccount);
						if (newBlock == null) {
							// For some reason we can't mint right now
							moderatedLog(() -> LOGGER.error("Couldn't build a to-be-minted block"));
							continue;
						}

						newBlocks.add(newBlock);
					} else {
						// The blocks for other minters require less effort...
						Block newBlock = newBlocks.get(0).remint(mintingAccount);
						if (newBlock == null) {
							// For some reason we can't mint right now
							moderatedLog(() -> LOGGER.error("Couldn't rebuild a to-be-minted block"));
							continue;
						}

						newBlocks.add(newBlock);
					}
				}

				// No potential block candidates?
				if (newBlocks.isEmpty())
					continue;

				// Make sure we're the only thread modifying the blockchain
				ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
				if (!blockchainLock.tryLock(30, TimeUnit.SECONDS)) {
					LOGGER.debug("Couldn't acquire blockchain lock even after waiting 30 seconds");
					continue;
				}

				boolean newBlockMinted = false;
				Block newBlock = null;

				try {
					// Clear repository session state so we have latest view of data
					repository.discardChanges();

					// Now that we have blockchain lock, do final check that chain hasn't changed
					BlockData latestBlockData = blockRepository.getLastBlock();
					if (!Arrays.equals(lastBlockData.getSignature(), latestBlockData.getSignature()))
						continue;

					List<Block> goodBlocks = new ArrayList<>();
					for (Block testBlock : newBlocks) {
						// Is new block's timestamp valid yet?
						// We do a separate check as some timestamp checks are skipped for testchains
						if (testBlock.isTimestampValid() != ValidationResult.OK)
							continue;

						testBlock.preProcess();

						// Is new block valid yet? (Before adding unconfirmed transactions)
						ValidationResult result = testBlock.isValid();
						if (result != ValidationResult.OK) {
							moderatedLog(() -> LOGGER.error(String.format("To-be-minted block invalid '%s' before adding transactions?", result.name())));

							continue;
						}

						goodBlocks.add(testBlock);
					}

					if (goodBlocks.isEmpty())
						continue;

					// Pick best block
					final int parentHeight = previousBlockData.getHeight();
					final byte[] parentBlockSignature = previousBlockData.getSignature();

					BigInteger bestWeight = null;

					for (int bi = 0; bi < goodBlocks.size(); ++bi) {
						BlockData blockData = goodBlocks.get(bi).getBlockData();

						BlockSummaryData blockSummaryData = new BlockSummaryData(blockData);
						int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, blockData.getMinterPublicKey());
						blockSummaryData.setMinterLevel(minterLevel);

						BigInteger blockWeight = Block.calcBlockWeight(parentHeight, parentBlockSignature, blockSummaryData);

						if (bestWeight == null || blockWeight.compareTo(bestWeight) < 0) {
							newBlock = goodBlocks.get(bi);
							bestWeight = blockWeight;
						}
					}

					try {
						if (this.higherWeightChainExists(repository, bestWeight)) {

							// Check if the base block has updated since the last time we were here
							if (parentSignatureForLastLowWeightBlock == null || timeOfLastLowWeightBlock == null ||
									!Arrays.equals(parentSignatureForLastLowWeightBlock, previousBlockData.getSignature())) {
								// We've switched to a different chain, so reset the timer
								timeOfLastLowWeightBlock = NTP.getTime();
							}
							parentSignatureForLastLowWeightBlock = previousBlockData.getSignature();

							// If less than 30 seconds has passed since first detection the higher weight chain,
							// we should skip our block submission to give us the opportunity to sync to the better chain
							if (NTP.getTime() - timeOfLastLowWeightBlock < 30*1000L) {
								LOGGER.debug("Higher weight chain found in peers, so not signing a block this round");
								LOGGER.debug("Time since detected: {}ms", NTP.getTime() - timeOfLastLowWeightBlock);
								continue;
							}
							else {
								// More than 30 seconds have passed, so we should submit our block candidate anyway.
								LOGGER.debug("More than 30 seconds passed, so proceeding to submit block candidate...");
							}
						}
						else {
							LOGGER.debug("No higher weight chain found in peers");
						}
					} catch (DataException e) {
						LOGGER.debug("Unable to check for a higher weight chain. Proceeding anyway...");
					}

					// Discard any uncommitted changes as a result of the higher weight chain detection
					repository.discardChanges();

					// Clear variables that track low weight blocks
					parentSignatureForLastLowWeightBlock = null;
					timeOfLastLowWeightBlock = null;


					// Add unconfirmed transactions
					addUnconfirmedTransactions(repository, newBlock);

					// Sign to create block's signature
					newBlock.sign();

					// Is newBlock still valid?
					ValidationResult validationResult = newBlock.isValid();
					if (validationResult != ValidationResult.OK) {
						// No longer valid? Report and discard
						LOGGER.error(String.format("To-be-minted block now invalid '%s' after adding unconfirmed transactions?", validationResult.name()));

						// Rebuild block candidates, just to be sure
						newBlocks.clear();
						continue;
					}

					// Add to blockchain - something else will notice and broadcast new block to network
					try {
						newBlock.process();

						repository.saveChanges();

						LOGGER.info(String.format("Minted new block: %d", newBlock.getBlockData().getHeight()));

						RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(newBlock.getBlockData().getMinterPublicKey());

						if (rewardShareData != null) {
							LOGGER.info(String.format("Minted block %d, sig %.8s, parent sig: %.8s by %s on behalf of %s",
									newBlock.getBlockData().getHeight(),
									Base58.encode(newBlock.getBlockData().getSignature()),
									Base58.encode(newBlock.getParent().getSignature()),
									rewardShareData.getMinter(),
									rewardShareData.getRecipient()));
						} else {
							LOGGER.info(String.format("Minted block %d, sig %.8s, parent sig: %.8s by %s",
									newBlock.getBlockData().getHeight(),
									Base58.encode(newBlock.getBlockData().getSignature()),
									Base58.encode(newBlock.getParent().getSignature()),
									newBlock.getMinter().getAddress()));
						}

						// Notify network after we're released blockchain lock
						newBlockMinted = true;

						// Notify Controller
						repository.discardChanges(); // clear transaction status to prevent deadlocks
						Controller.getInstance().onNewBlock(newBlock.getBlockData());
					} catch (DataException e) {
						// Unable to process block - report and discard
						LOGGER.error("Unable to process newly minted block?", e);
						newBlocks.clear();
					}
				} finally {
					blockchainLock.unlock();
				}

				if (newBlockMinted) {
					// Broadcast our new chain to network
					BlockData newBlockData = newBlock.getBlockData();

					Network network = Network.getInstance();
					network.broadcast(broadcastPeer -> network.buildHeightMessage(broadcastPeer, newBlockData));
				}
			}
		} catch (DataException e) {
			LOGGER.warn("Repository issue while running block minter", e);
		} catch (InterruptedException e) {
			// We've been interrupted - time to exit
			return;
		}
	}

	/**
	 * Adds unconfirmed transactions to passed block.
	 * <p>
	 * NOTE: calls Transaction.getUnconfirmedTransactions which discards uncommitted
	 * repository changes.
	 * 
	 * @param repository
	 * @param newBlock
	 * @throws DataException
	 */
	private static void addUnconfirmedTransactions(Repository repository, Block newBlock) throws DataException {
		// Grab all valid unconfirmed transactions (already sorted)
		List<TransactionData> unconfirmedTransactions = Transaction.getUnconfirmedTransactions(repository);

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		final long newBlockTimestamp = newBlock.getBlockData().getTimestamp();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();

			// Ignore transactions that have timestamp later than block's timestamp (not yet valid)
			// Ignore transactions that have expired before this block - they will be cleaned up later
			if (transactionData.getTimestamp() > newBlockTimestamp || Transaction.getDeadline(transactionData) <= newBlockTimestamp)
				unconfirmedTransactionsIterator.remove();
		}

		// Sign to create block's signature, needed by Block.isValid()
		newBlock.sign();

		// Attempt to add transactions until block is full, or we run out
		// If a transaction makes the block invalid then skip it and it'll either expire or be in next block.
		for (TransactionData transactionData : unconfirmedTransactions) {
			if (!newBlock.addTransaction(transactionData))
				break;

			// If newBlock is no longer valid then we can't use transaction
			ValidationResult validationResult = newBlock.isValid();
			if (validationResult != ValidationResult.OK) {
				LOGGER.debug(() -> String.format("Skipping invalid transaction %s during block minting", Base58.encode(transactionData.getSignature())));
				newBlock.deleteTransaction(transactionData);
			}
		}
	}

	public void shutdown() {
		this.running = false;
		// Interrupt too, absorbed by HSQLDB but could be caught by Thread.sleep()
		this.interrupt();
	}

	public static Block mintTestingBlock(Repository repository, PrivateKeyAccount... mintingAndOnlineAccounts) throws DataException {
		if (!BlockChain.getInstance().isTestChain())
			throw new DataException("Ignoring attempt to mint testing block for non-test chain!");

		// Ensure mintingAccount is 'online' so blocks can be minted
		OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(mintingAndOnlineAccounts);

		PrivateKeyAccount mintingAccount = mintingAndOnlineAccounts[0];

		return mintTestingBlockRetainingTimestamps(repository, mintingAccount);
	}

	public static Block mintTestingBlockRetainingTimestamps(Repository repository, PrivateKeyAccount mintingAccount) throws DataException {
		BlockData previousBlockData = repository.getBlockRepository().getLastBlock();

		Block newBlock = Block.mint(repository, previousBlockData, mintingAccount);

		// Make sure we're the only thread modifying the blockchain
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		blockchainLock.lock();
		try {
			// Add unconfirmed transactions
			addUnconfirmedTransactions(repository, newBlock);

			// Sign to create block's signature
			newBlock.sign();

			// Is newBlock still valid?
			ValidationResult validationResult = newBlock.isValid();
			if (validationResult != ValidationResult.OK)
				throw new IllegalStateException(String.format("To-be-minted test block now invalid '%s' after adding unconfirmed transactions?", validationResult.name()));

			// Add to blockchain
			newBlock.process();
			LOGGER.info(String.format("Minted new test block: %d sig: %.8s",
					newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

			repository.saveChanges();

			return newBlock;
		} finally {
			blockchainLock.unlock();
		}
	}

	private BigInteger getOurChainWeightSinceBlock(Repository repository, BlockSummaryData commonBlock, List<BlockSummaryData> peerBlockSummaries) throws DataException {
		final int commonBlockHeight = commonBlock.getHeight();
		final byte[] commonBlockSig = commonBlock.getSignature();
		int mutualHeight = commonBlockHeight;

		// Fetch our corresponding block summaries
		final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
		List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository()
				.getBlockSummaries(commonBlockHeight + 1, ourLatestBlockData.getHeight());
		if (!ourBlockSummaries.isEmpty()) {
			Synchronizer.getInstance().populateBlockSummariesMinterLevels(repository, ourBlockSummaries);
		}

		if (ourBlockSummaries != null && peerBlockSummaries != null) {
			mutualHeight += Math.min(ourBlockSummaries.size(), peerBlockSummaries.size());
		}
		return Block.calcChainWeight(commonBlockHeight, commonBlockSig, ourBlockSummaries, mutualHeight);
	}

	private boolean higherWeightChainExists(Repository repository, BigInteger blockCandidateWeight) throws DataException {
		if (blockCandidateWeight == null) {
			// Can't make decisions without knowing the block candidate weight
			return false;
		}
		NumberFormat formatter = new DecimalFormat("0.###E0");

		List<Peer> peers = Network.getInstance().getImmutableHandshakedPeers();
		// Loop through handshaked peers and check for any new block candidates
		for (Peer peer : peers) {
			if (peer.getCommonBlockData() != null && peer.getCommonBlockData().getCommonBlockSummary() != null) {
				// This peer has common block data
				CommonBlockData commonBlockData = peer.getCommonBlockData();
				BlockSummaryData commonBlockSummaryData = commonBlockData.getCommonBlockSummary();
				if (commonBlockData.getChainWeight() != null) {
					// The synchronizer has calculated this peer's chain weight
					BigInteger ourChainWeightSinceCommonBlock = this.getOurChainWeightSinceBlock(repository, commonBlockSummaryData, commonBlockData.getBlockSummariesAfterCommonBlock());
					BigInteger ourChainWeight = ourChainWeightSinceCommonBlock.add(blockCandidateWeight);
					BigInteger peerChainWeight = commonBlockData.getChainWeight();
					if (peerChainWeight.compareTo(ourChainWeight) >= 0) {
						// This peer has a higher weight chain than ours
						LOGGER.debug("Peer {} is on a higher weight chain ({}) than ours ({})", peer, formatter.format(peerChainWeight), formatter.format(ourChainWeight));
						return true;

					} else {
						LOGGER.debug("Peer {} is on a lower weight chain ({}) than ours ({})", peer, formatter.format(peerChainWeight), formatter.format(ourChainWeight));
					}
				} else {
					LOGGER.debug("Peer {} has no chain weight", peer);
				}
			} else {
				LOGGER.debug("Peer {} has no common block data", peer);
			}
		}
		return false;
	}
	private static void moderatedLog(Runnable logFunction) {
		// We only log if logging at TRACE or previous log timeout has expired
		if (!LOGGER.isTraceEnabled() && lastLogTimestamp != null && lastLogTimestamp + logTimeout > System.currentTimeMillis())
			return;

		lastLogTimestamp = System.currentTimeMillis();
		logTimeout = 2 * 60 * 1000L; // initial timeout, can be reduced if new block appears

		logFunction.run();
	}

}
