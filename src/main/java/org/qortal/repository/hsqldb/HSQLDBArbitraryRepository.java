package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.*;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.ArbitraryRepository;
import org.qortal.repository.DataException;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.transaction.Transaction.ApprovalStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HSQLDBArbitraryRepository implements ArbitraryRepository {

	private static final int MAX_RAW_DATA_SIZE = 255; // size of VARBINARY

	protected HSQLDBRepository repository;
	
	public HSQLDBArbitraryRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	private ArbitraryTransactionData getTransactionData(byte[] signature) throws DataException {
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(signature);
		if (transactionData == null)
			return null;

		return (ArbitraryTransactionData) transactionData;
	}

	@Override
	public boolean isDataLocal(byte[] signature) throws DataException {
		ArbitraryTransactionData transactionData = getTransactionData(signature);
		if (transactionData == null) {
			return false;
		}

		// Raw data is always available
		if (transactionData.getDataType() == DataType.RAW_DATA) {
			return true;
		}

		// Load hashes
		byte[] digest = transactionData.getData();
		byte[] chunkHashes = transactionData.getChunkHashes();

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
		if (chunkHashes != null && chunkHashes.length > 0) {
			arbitraryDataFile.addChunkHashes(chunkHashes);
		}

		// Check if we already have the complete data file
		if (arbitraryDataFile.exists()) {
			return true;
		}

		// If this transaction doesn't have any chunks, then we require the complete file
		if (chunkHashes == null) {
			return false;
		}

		// Alternatively, if we have all the chunks, then it's safe to assume the data is local
		if (arbitraryDataFile.allChunksExist(chunkHashes)) {
			return true;
		}

		return false;
	}

	@Override
	public byte[] fetchData(byte[] signature) throws DataException {
		ArbitraryTransactionData transactionData = getTransactionData(signature);
		if (transactionData == null) {
			return null;
		}

		// Raw data is always available
		if (transactionData.getDataType() == DataType.RAW_DATA) {
			return transactionData.getData();
		}

		// Load hashes
		byte[] digest = transactionData.getData();
		byte[] chunkHashes = transactionData.getChunkHashes();

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
		if (chunkHashes != null && chunkHashes.length > 0) {
			arbitraryDataFile.addChunkHashes(chunkHashes);
		}

		// If we have the complete data file, return it
		if (arbitraryDataFile.exists()) {
			return arbitraryDataFile.getBytes();
		}

		// Alternatively, if we have all the chunks, combine them into a single file
		if (arbitraryDataFile.allChunksExist(chunkHashes)) {
			arbitraryDataFile.join();

			// Verify that the combined hash matches the expected hash
			if (digest.equals(arbitraryDataFile.digest())) {
				return arbitraryDataFile.getBytes();
			}
		}

		return null;
	}

	@Override
	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// Already hashed? Nothing to do
		if (arbitraryTransactionData.getDataType() == DataType.DATA_HASH) {
			return;
		}

		// Trivial-sized payloads can remain in raw form
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA && arbitraryTransactionData.getData().length <= MAX_RAW_DATA_SIZE) {
			return;
		}

		throw new IllegalStateException(String.format("Supplied data is larger than maximum size (%i bytes). Please use ArbitraryDataWriter.", MAX_RAW_DATA_SIZE));
	}

	@Override
	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException {
		// No need to do anything if we still only have raw data, and hence nothing saved in filesystem
		if (arbitraryTransactionData.getDataType() == DataType.RAW_DATA) {
			return;
		}

		// Load hashes
		byte[] digest = arbitraryTransactionData.getData();
		byte[] chunkHashes = arbitraryTransactionData.getChunkHashes();

		// Load data file(s)
		ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(digest);
		if (chunkHashes != null && chunkHashes.length > 0) {
			arbitraryDataFile.addChunkHashes(chunkHashes);
		}

		// Delete file and chunks
		arbitraryDataFile.deleteAll();
	}

	@Override
	public List<ArbitraryTransactionData> getArbitraryTransactions(String name, Service service, long since) throws DataException {
		String sql = "SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, chunk_hashes, " +
				"name, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE name = ? AND service = ? AND created_when >= ?" +
				"ORDER BY created_when ASC";
		List<ArbitraryTransactionData> arbitraryTransactionData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, name, service.value, since)) {
			if (resultSet == null)
				return null;

			do {
				//TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

				byte[] reference = resultSet.getBytes(2);
				byte[] signature = resultSet.getBytes(3);
				byte[] creatorPublicKey = resultSet.getBytes(4);
				long timestamp = resultSet.getLong(5);

				Long fee = resultSet.getLong(6);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(7);

				Integer blockHeight = resultSet.getInt(8);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
				Integer approvalHeight = resultSet.getInt(10);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

				int version = resultSet.getInt(11);
				int nonce = resultSet.getInt(12);
				Service serviceResult = Service.valueOf(resultSet.getInt(13));
				int size = resultSet.getInt(14);
				boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
				DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
				byte[] data = resultSet.getBytes(16);
				byte[] chunkHashes = resultSet.getBytes(17);
				String nameResult = resultSet.getString(18);
				Method method = Method.valueOf(resultSet.getInt(19));
				byte[] secret = resultSet.getBytes(20);
				Compression compression = Compression.valueOf(resultSet.getInt(21));

				List<PaymentData> payments = new ArrayList<>(); // TODO: this.getPaymentsFromSignature(baseTransactionData.getSignature());
				ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
						version, serviceResult, nonce, size, nameResult, method, secret, compression, data,
						dataType, chunkHashes, payments);

				arbitraryTransactionData.add(transactionData);
			} while (resultSet.next());

			return arbitraryTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}

	@Override
	public ArbitraryTransactionData getLatestTransaction(String name, Service service, Method method) throws DataException {
		StringBuilder sql = new StringBuilder(1024);

		sql.append("SELECT type, reference, signature, creator, created_when, fee, " +
				"tx_group_id, block_height, approval_status, approval_height, " +
				"version, nonce, service, size, is_data_raw, data, chunk_hashes, " +
				"name, update_method, secret, compression FROM ArbitraryTransactions " +
				"JOIN Transactions USING (signature) " +
				"WHERE name = ? AND service = ?");

		if (method != null) {
			sql.append(" AND update_method = ");
			sql.append(method.value);
		}

		sql.append("ORDER BY created_when DESC LIMIT 1");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), name, service.value)) {
			if (resultSet == null)
				return null;

			//TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

			byte[] reference = resultSet.getBytes(2);
			byte[] signature = resultSet.getBytes(3);
			byte[] creatorPublicKey = resultSet.getBytes(4);
			long timestamp = resultSet.getLong(5);

			Long fee = resultSet.getLong(6);
			if (fee == 0 && resultSet.wasNull())
				fee = null;

			int txGroupId = resultSet.getInt(7);

			Integer blockHeight = resultSet.getInt(8);
			if (blockHeight == 0 && resultSet.wasNull())
				blockHeight = null;

			ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(9));
			Integer approvalHeight = resultSet.getInt(10);
			if (approvalHeight == 0 && resultSet.wasNull())
				approvalHeight = null;

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, creatorPublicKey, fee, approvalStatus, blockHeight, approvalHeight, signature);

			int version = resultSet.getInt(11);
			int nonce = resultSet.getInt(12);
			Service serviceResult = Service.valueOf(resultSet.getInt(13));
			int size = resultSet.getInt(14);
			boolean isDataRaw = resultSet.getBoolean(15); // NOT NULL, so no null to false
			DataType dataType = isDataRaw ? DataType.RAW_DATA : DataType.DATA_HASH;
			byte[] data = resultSet.getBytes(16);
			byte[] chunkHashes = resultSet.getBytes(17);
			String nameResult = resultSet.getString(18);
			Method methodResult = Method.valueOf(resultSet.getInt(19));
			byte[] secret = resultSet.getBytes(20);
			Compression compression = Compression.valueOf(resultSet.getInt(21));

			List<PaymentData> payments = new ArrayList<>(); // TODO: this.getPaymentsFromSignature(baseTransactionData.getSignature());
			ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
					version, serviceResult, nonce, size, nameResult, methodResult, secret, compression, data,
					dataType, chunkHashes, payments);

			return transactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch arbitrary transactions from repository", e);
		}
	}

}
