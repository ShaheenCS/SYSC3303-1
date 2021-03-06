public class DataPacket {
	public static final byte[] opcode = {0, 3};
	private byte[] data;
	private byte[] dataBlock;
	int blockNum;
	
	public byte[] getDataBlock() {
		return dataBlock;
	}
	public int getBlockNum() {
		return blockNum;
	}
	
	public static boolean isValid(byte[] data) {
		if (data[0] != opcode[0] || data[1] != opcode[1]) return false;
		if (data.length > 516) return false;
		
		return true;
	}
	
	public DataPacket(byte[] data) {
		this.data = data;
		blockNum = data[2] & 0x00FF;
		blockNum <<= 8;
		blockNum |= data[3] & 0x0FF;
		dataBlock = new byte[data.length - 4];
		System.arraycopy(data, 4, dataBlock, 0, data.length - 4);
	}
	
	public DataPacket(int blockNum, byte[] dataBlock) {
		this.blockNum = blockNum;
		this.dataBlock = dataBlock;
		
		data = new byte[4 + dataBlock.length];
		data[0] = opcode[0];
		data[1] = opcode[1];
		data[2] = (byte)((blockNum >> 8) & 0xFF);
		data[3] = (byte)(blockNum & 0xFF);
		
		System.arraycopy(dataBlock, 0, data, 4, dataBlock.length);
		
	}
	
	public byte[] encode() {
		return data;
	}
}
