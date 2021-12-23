package org.qortal.controller.arbitrary;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.controller.Controller;
import org.qortal.data.network.ArbitraryPeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

public class ArbitraryDataManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	/** Request timeout when transferring arbitrary data */
	public static final long ARBITRARY_REQUEST_TIMEOUT = 6 * 1000L; // ms

	/** Maximum time to hold information about an in-progress relay */
	public static final long ARBITRARY_RELAY_TIMEOUT = 30 * 1000L; // ms

	private static ArbitraryDataManager instance;
	private final Object peerDataLock = new Object();

	private volatile boolean isStopping = false;

	/**
	 * Map to keep track of cached arbitrary transaction resources.
	 * When an item is present in this list with a timestamp in the future, we won't invalidate
	 * its cache when serving that data. This reduces the amount of database lookups that are needed.
	 */
	private Map<String, Long> arbitraryDataCachedResources = Collections.synchronizedMap(new HashMap<>());

	/**
	 * The amount of time to cache a data resource before it is invalidated
	 */
	private static long ARBITRARY_DATA_CACHE_TIMEOUT = 60 * 60 * 1000L; // 60 minutes



	private ArbitraryDataManager() {
	}

	public static ArbitraryDataManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Manager");

		ArbitraryDataFileListManager.getInstance().start();
		ArbitraryDataFileManager.getInstance().start();

		try {
			while (!isStopping) {
				Thread.sleep(2000);

				// Don't run if QDN is disabled
				if (!Settings.getInstance().isQdnEnabled()) {
					Thread.sleep(60 * 60 * 1000L);
					continue;
				}

				List<Peer> peers = Network.getInstance().getHandshakedPeers();

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't fetch data if we don't have enough up-to-date peers
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					continue;
				}

				// Fetch data according to storage policy
				switch (Settings.getInstance().getStoragePolicy()) {
					case FOLLOWED:
					case FOLLOWED_AND_VIEWED:
						this.processNames();
						break;

					case ALL:
						this.processAll();

					case NONE:
					case VIEWED:
					default:
						// Nothing to fetch in advance
						Thread.sleep(60000);
						break;
				}
			}
		} catch (InterruptedException e) {
			// Fall-through to exit thread...
		}
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();

		ArbitraryDataFileListManager.getInstance().shutdown();
		ArbitraryDataFileManager.getInstance().shutdown();
	}

	private void processNames() {
		// Fetch latest list of followed names
		List<String> followedNames = ResourceListManager.getInstance().getStringsInList("followedNames");
		if (followedNames == null || followedNames.isEmpty()) {
			return;
		}

		// Loop through the names in the list and fetch transactions for each
		for (String name : followedNames) {
			this.fetchAndProcessTransactions(name);
		}
	}

	private void processAll() {
		this.fetchAndProcessTransactions(null);
	}

	private void fetchAndProcessTransactions(String name) {
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		while (!isStopping) {

			// Any arbitrary transactions we want to fetch data for?
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, ARBITRARY_TX_TYPE, null, name, null, ConfirmationStatus.BOTH, limit, offset, true);
				// LOGGER.info("Found {} arbitrary transactions at offset: {}, limit: {}", signatures.size(), offset, limit);
				if (signatures == null || signatures.isEmpty()) {
					offset = 0;
					break;
				}
				offset += limit;

				// Loop through signatures and remove ones we don't need to process
				Iterator iterator = signatures.iterator();
				while (iterator.hasNext()) {
					byte[] signature = (byte[]) iterator.next();

					ArbitraryTransaction arbitraryTransaction = fetchTransaction(repository, signature);
					if (arbitraryTransaction == null) {
						// Best not to process this one
						iterator.remove();
						continue;
					}
					ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();

					// Skip transactions that we don't need to proactively store data for
					if (!storageManager.shouldPreFetchData(repository, arbitraryTransactionData)) {
						iterator.remove();
						continue;
					}

					// Remove transactions that we already have local data for
					if (hasLocalData(arbitraryTransaction)) {
						iterator.remove();
						continue;
					}
				}

				if (signatures.isEmpty()) {
					continue;
				}

				// Pick one at random
				final int index = new Random().nextInt(signatures.size());
				byte[] signature = signatures.get(index);

				if (signature == null) {
					continue;
				}

				// Check to see if we have had a more recent PUT
				ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);
				boolean hasMoreRecentPutTransaction = ArbitraryTransactionUtils.hasMoreRecentPutTransaction(repository, arbitraryTransactionData);
				if (hasMoreRecentPutTransaction) {
					// There is a more recent PUT transaction than the one we are currently processing.
					// When a PUT is issued, it replaces any layers that would have been there before.
					// Therefore any data relating to this older transaction is no longer needed and we
					// shouldn't fetch it from the network.
					continue;
				}

				// Ask our connected peers if they have files for this signature
				// This process automatically then fetches the files themselves if a peer is found
				fetchData(arbitraryTransactionData);

			} catch (DataException e) {
				LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
			}
		}
	}

	private ArbitraryTransaction fetchTransaction(final Repository repository, byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return null;

			return new ArbitraryTransaction(repository, transactionData);

		} catch (DataException e) {
			return null;
		}
	}

	private boolean hasLocalData(ArbitraryTransaction arbitraryTransaction) {
		try {
			return arbitraryTransaction.isDataLocal();

		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's data is local", e);
			return true;
		}
	}


	// Entrypoint to request new data from peers
	public boolean fetchData(ArbitraryTransactionData arbitraryTransactionData) {
		return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(arbitraryTransactionData);
	}


	// Useful methods used by other parts of the app

	public boolean isSignatureRateLimited(byte[] signature) {
		return ArbitraryDataFileListManager.getInstance().isSignatureRateLimited(signature);
	}

	public long lastRequestForSignature(byte[] signature) {
		return ArbitraryDataFileListManager.getInstance().lastRequestForSignature(signature);
	}


	// Arbitrary data resource cache

	public void cleanupRequestCache(Long now) {
		if (now == null) {
			return;
		}

		// Cleanup file list request caches
		ArbitraryDataFileListManager.getInstance().cleanupRequestCache(now);

		// Cleanup file request caches
		ArbitraryDataFileManager.getInstance().cleanupRequestCache(now);
	}

	public boolean isResourceCached(String resourceId) {
		if (resourceId == null) {
			return false;
		}
		resourceId = resourceId.toLowerCase();

		// We don't have an entry for this resource ID, it is not cached
		if (this.arbitraryDataCachedResources == null) {
			return false;
		}
		if (!this.arbitraryDataCachedResources.containsKey(resourceId)) {
			return false;
		}
		Long timestamp = this.arbitraryDataCachedResources.get(resourceId);
		if (timestamp == null) {
			return false;
		}

		// If the timestamp has reached the timeout, we should remove it from the cache
		long now = NTP.getTime();
		if (now > timestamp) {
			this.arbitraryDataCachedResources.remove(resourceId);
			return false;
		}

		// Current time hasn't reached the timeout, so treat it as cached
		return true;
	}

	public void addResourceToCache(String resourceId) {
		if (resourceId == null) {
			return;
		}
		resourceId = resourceId.toLowerCase();

		// Just in case
		if (this.arbitraryDataCachedResources == null) {
			this.arbitraryDataCachedResources = new HashMap<>();
		}

		Long now = NTP.getTime();
		if (now == null) {
			return;
		}

		// Set the timestamp to now + the timeout
		Long timestamp = NTP.getTime() + ARBITRARY_DATA_CACHE_TIMEOUT;
		this.arbitraryDataCachedResources.put(resourceId, timestamp);
	}

	public void invalidateCache(ArbitraryTransactionData arbitraryTransactionData) {
		String signature58 = Base58.encode(arbitraryTransactionData.getSignature());

		if (arbitraryTransactionData.getName() != null) {
			String resourceId = arbitraryTransactionData.getName().toLowerCase();
			LOGGER.info("We have all data for transaction {}", signature58);
			LOGGER.info("Clearing cache for name {}...", arbitraryTransactionData.getName());

			if (this.arbitraryDataCachedResources.containsKey(resourceId)) {
				this.arbitraryDataCachedResources.remove(resourceId);
			}

			// Also remove from the failed builds queue in case it previously failed due to missing chunks
			ArbitraryDataBuildManager buildManager = ArbitraryDataBuildManager.getInstance();
			if (buildManager.arbitraryDataFailedBuilds.containsKey(resourceId)) {
				buildManager.arbitraryDataFailedBuilds.remove(resourceId);
			}

			// Remove from the signature requests list now that we have all files for this signature
			ArbitraryDataFileListManager.getInstance().removeFromSignatureRequests(signature58);
		}
	}


	// Handle incoming arbitrary signatures messages

	public void onNetworkArbitrarySignaturesMessage(Peer peer, Message message) {
		// Don't process if QDN is disabled
		if (!Settings.getInstance().isQdnEnabled()) {
			return;
		}

		LOGGER.info("Received arbitrary signature list from peer {}", peer);

		ArbitrarySignaturesMessage arbitrarySignaturesMessage = (ArbitrarySignaturesMessage) message;
		List<byte[]> signatures = arbitrarySignaturesMessage.getSignatures();

		String peerAddress = peer.getPeerData().getAddress().toString();
		if (arbitrarySignaturesMessage.getPeerAddress() != null) {
			// This message is about a different peer than the one that sent it
			peerAddress = arbitrarySignaturesMessage.getPeerAddress();
		}

		boolean containsNewEntry = false;

		// Synchronize peer data lookups to make this process thread safe. Otherwise we could broadcast
		// the same data multiple times, due to more than one thread processing the same message from different peers
		synchronized (this.peerDataLock) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				for (byte[] signature : signatures) {

					// Check if a record already exists for this hash/peer combination
					ArbitraryPeerData existingEntry = repository.getArbitraryRepository()
							.getArbitraryPeerDataForSignatureAndPeer(signature, peer.getPeerData().getAddress().toString());

					if (existingEntry == null) {
						// We haven't got a record of this mapping yet, so add it
						LOGGER.info("Adding arbitrary peer: {} for signature {}", peerAddress, Base58.encode(signature));
						ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
						repository.getArbitraryRepository().save(arbitraryPeerData);
						repository.saveChanges();

						// Remember that this data is new, so that it can be rebroadcast later
						containsNewEntry = true;
					}
				}

				// If at least one signature in this batch was new to us, we should rebroadcast the message to the
				// network in case some peers haven't received it yet
				if (containsNewEntry) {
					LOGGER.info("Rebroadcasting arbitrary signature list for peer {}", peerAddress);
					Network.getInstance().broadcast(broadcastPeer -> broadcastPeer == peer ? null : arbitrarySignaturesMessage);
				} else {
					// Don't rebroadcast as otherwise we could get into a loop
				}

				// If anything needed saving, it would already have called saveChanges() above
				repository.discardChanges();
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while processing arbitrary transaction signature list from peer %s", peer), e);
			}
		}
	}

}
