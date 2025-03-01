package org.qortal.network.message;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transform.TransformationException;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.Triple;

public class BlockMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(BlockMessage.class);

	private final BlockData blockData;
	private final List<TransactionData> transactions;
	private final List<ATStateData> atStates;

	// No public constructor as we're an incoming-only message type.

	private BlockMessage(int id, BlockData blockData, List<TransactionData> transactions, List<ATStateData> atStates) {
		super(id, MessageType.BLOCK);

		this.blockData = blockData;
		this.transactions = transactions;
		this.atStates = atStates;
	}

	public BlockData getBlockData() {
		return this.blockData;
	}

	public List<TransactionData> getTransactions() {
		return this.transactions;
	}

	public List<ATStateData> getAtStates() {
		return this.atStates;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		try {
			int height = byteBuffer.getInt();

			Triple<BlockData, List<TransactionData>, List<ATStateData>> blockInfo = BlockTransformer.fromByteBuffer(byteBuffer);

			BlockData blockData = blockInfo.getA();
			blockData.setHeight(height);

			return new BlockMessage(id, blockData, blockInfo.getB(), blockInfo.getC());
		} catch (TransformationException e) {
			LOGGER.info(String.format("Received garbled BLOCK message: %s", e.getMessage()));
			throw new MessageException(e.getMessage(), e);
		}
	}

}
