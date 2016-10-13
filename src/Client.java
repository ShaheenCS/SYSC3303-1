import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.util.Arrays;
import java.util.Scanner;

public class Client {
	// Opcodes
	private static final byte[] readReqOP = {0, 1};
	private static final byte[] writeReqOP = {0, 2};
	private static final byte[] dataOP = {0, 3};
	private static final byte[] ackOP = {0, 4};
	private static final byte[] errorOP = {0, 5};
	
	private boolean testMode = false;
	private int wellKnownPort;
	DatagramPacket sendPacket, receivePacket;
	DatagramSocket sendAndReceiveSocket;

	public boolean isTestMode() {
		return testMode;
	}

	public void setTestMode(boolean testMode) {
		if (testMode) wellKnownPort = 23;
		else wellKnownPort = 69;
		this.testMode = testMode;
	}

	
	/*
	 * Constructor
	 * Initializes the Datagram Socket
	 */
	public Client() {
		try {
			sendAndReceiveSocket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
			System.exit(1);
		}	
	}

	private void sendRequest(byte[] reqType, String filename, String mode) throws UnknownHostException {
		byte[] message = formatRequest(reqType, filename, mode);
		
		DatagramPacket requestPacket = new DatagramPacket(message, message.length, InetAddress.getLocalHost(), wellKnownPort);
		
		try {
			sendAndReceiveSocket.send(requestPacket);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void readFromServer(String filename, String mode) throws IOException{
		//check if a file with that name exists on the client side
		File f = new File("ClientFiles/" + filename);
		if(f.exists()){
			String msg = filename +" already exists on client side";
			System.err.println(msg);
			ErrorPacket errPckt = new ErrorPacket((byte) 6, msg);
			byte[] err = errPckt.encode();
			sendPacket = new DatagramPacket(err, err.length, InetAddress.getLocalHost(), wellKnownPort);
			sendAndReceiveSocket.send(sendPacket);
			System.exit(1);
		}
		
		System.out.println("Initiating read request with file " + filename);
		
		sendRequest(readReqOP, filename, mode);
		byte[] receivedData;
		byte[] receivedOpcode;
		int currentBlockNumber = 1;
		
		BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream("ClientFiles/" + filename));
		
		while (true) {
			receivedData = new byte[2 + 2 + 512]; // opcode + blockNumber + 512 bytes of data
			receivePacket = new DatagramPacket(receivedData, receivedData.length);
			System.out.println("Waiting for block of data...");
			// receive block
			try{
				sendAndReceiveSocket.receive(receivePacket);
			}catch(IOException e)
			{
				if(e.getCause() instanceof FileNotFoundException)
				{
					System.err.println("Error: File not found");
					return;
				}
				else if(e.getCause() instanceof AccessDeniedException)
				{
					System.err.println("Error: Access denied");
					return;
				}
				else
				{
					throw new IOException();
				}
			}
			TFTPInfoPrinter.printReceived(receivePacket);
			
			// validate packet
			receivedData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
			receivedOpcode = Arrays.copyOf(receivedData, 2);

			
			if (Arrays.equals(receivedOpcode, errorOP)){
				// Determine error code.
				// Handle error.
			}
			// The received packet should be an DATA packet at this point, and this have the Opcode defined in dataOP.
			// If it is not an error packet or an DATA packet, something happened (these cases are in later iterations).
			else if (!Arrays.equals(receivedOpcode, dataOP)) {
				// Do nothing special for Iteration 2.
			}

			
			int blockNum = getBlockNumberInt(receivedData);
			System.out.println("Received block of data, Block#: " + currentBlockNumber);
			
			// Note: 256 is the maximum size of a 16 bit number.
			if (blockNum != currentBlockNumber) {
				if (blockNum < 0) {
					blockNum += 256; // If the block rolls over (it's a 16 bit number represented as unsigned)
				}
				 // If they're still not equal, another problem occurred.
				if (blockNum != currentBlockNumber % 256)
				{
					// This will likely need to be handled different in future iterations.
					System.out.println("Block Numbers not the same, exiting " + blockNum + " " + currentBlockNumber + " " + currentBlockNumber % 256);
					System.exit(1);
				}
			}
			byte[] dataBlock = Arrays.copyOfRange(receivedData, 4, receivedData.length); // 4 is where the data starts, after opcode + blockNumber
			
			// Write dataBlock to file
			try{
				out.write(dataBlock);
			}
			catch(IOException e){ //disk full
				String msg = "Unable to write file " +filename+", disk space full";
				System.err.println(msg);
				ErrorPacket errPckt = new ErrorPacket((byte) 3, msg);
				byte[] err = errPckt.encode();
				sendPacket = new DatagramPacket(err, err.length, InetAddress.getLocalHost(), receivePacket.getPort());
				sendAndReceiveSocket.send(sendPacket);
				System.exit(1);
			}
			
			// At this point a file IO may have occurred and an error packet needs to be sent.
			
			
			System.out.println("Sending Ack...");
			// Send ack back
			byte[] ack = createAck(blockNum);
			
			// Initial request was sent to wellKnownPort, but steady state file transfer should happen on another port.
			sendPacket = new DatagramPacket(ack, ack.length, InetAddress.getLocalHost(), receivePacket.getPort());
			sendAndReceiveSocket.send(sendPacket);
			TFTPInfoPrinter.printSent(sendPacket);
			currentBlockNumber++;
			
			// check if block is < 512 bytes which signifies end of file
			if (dataBlock.length < 512) { 
				System.out.println("Data was received that was less than 512 bytes in length");
				System.out.println("Total transfers that took place: " + blockNum);
				break; 
			}
		}
		out.close();
	}
	
	public void writeToServer(String filename, String mode) throws IOException {
		
		int currentBlockNumber = 0;
		BufferedInputStream in;
		byte[] receivedData;
		byte[] receivedOpcode;
		// It's a full path
		if (filename.contains("\\") || filename.contains("/")) {
			 in = new BufferedInputStream(new FileInputStream(filename));
			 // for sending to Server
			 int idx = filename.lastIndexOf('\\');
			 if (idx == -1) {
				 idx = filename.lastIndexOf('/');
			 } 
			 filename = filename.substring(idx+1);
			 
		}
		// It's in the default ClientFiles folder
		else {
			 in = new BufferedInputStream(new FileInputStream("ClientFiles/" + filename));
		}
		
		sendRequest(writeReqOP, filename, mode);
		
		while (true) {
			// receive ACK from previous dataBlock
			byte[] data = new byte[4];
			receivePacket = new DatagramPacket(data, data.length);
			System.out.println("Client is waiting to receive ACK from server");
			try
			{
				sendAndReceiveSocket.receive(receivePacket);
			}catch(IOException e)
			{
				if(e.getCause() instanceof AccessDeniedException)
				{
					System.err.println("Error: Access denied." );
					return;
				}
				else
				{
					throw new IOException();
				}
			}			TFTPInfoPrinter.printReceived(receivePacket);
			
			receivedData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
			receivedOpcode = Arrays.copyOf(receivedData, 2);
			
			if (Arrays.equals(receivedOpcode, errorOP)){
				// Determine error code.
				// Handle error.
			}
			// The received packet should be an ACK packet at this point, and this have the Opcode defined in ackOP.
			// If it is not an error packet or an ACK packet, something happened (these cases are in later iterations).
			else if (!Arrays.equals(receivedOpcode, ackOP)) {
				// Do nothing special for Iteration 2.
			}
			
			// need block number
			int blockNum = getBlockNumberInt(receivedData);
			
			// Note: 256 is the maximum size of a 16 bit number.
			// blockNum is an unsigned number, represented as a 2s complement it will appear to go from 127 to -128
			if (blockNum != currentBlockNumber) {
				if (blockNum < 0) {
					blockNum += 256; // If the block rolls over (it's a 16 bit number represented as unsigned)
				}
				// If they're still not equal, another problem occurred.
				if (blockNum != currentBlockNumber % 256)
				{
					System.out.println("Block Numbers not the same, exiting " + blockNum + " " + currentBlockNumber + " " + currentBlockNumber % 256);
					System.exit(1);
				}	
			}
			
			// increment block number then send that block
			currentBlockNumber++;
			
			byte[] dataBlock = new byte[512];
			
			// Resize dataBlock to total bytes read
			int bytesRead = in.read(dataBlock);
			if (bytesRead == -1) bytesRead = 0;
			dataBlock = Arrays.copyOf(dataBlock, bytesRead);
			
			byte[] sendData = formatData(dataBlock, currentBlockNumber);
			// Initial request was sent to wellKnownPort, but steady state file transfer should happen on another port.
			sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getLocalHost(), receivePacket.getPort());
			sendAndReceiveSocket.send(sendPacket);
			
			TFTPInfoPrinter.printSent(sendPacket);
			
			if (bytesRead < 512) break;
		}
		
		in.close();
	}
	
	private byte[] createAck(int blockNum) {
		byte[] ack = new byte[4];
		ack[0] = ackOP[0]; //
		ack[1] = ackOP[1]; // Opcode
		byte[] bn = convertBlockNumberByteArr(blockNum);
		ack[2] = bn[0];
		ack[3] = bn[1];
		
		return ack;
	}
	
	private int getBlockNumberInt(byte[] data) {
		int blockNum;
		// Check opcodes
		
		// Big Endian 
		blockNum = data[2];
		blockNum <<= 8;
		blockNum |= data[3];
		// 
		return blockNum;
	}
	private byte[] convertBlockNumberByteArr(int blockNumber) {
		return new byte[] {(byte)((blockNumber >> 8) & 0xFF), (byte)(blockNumber & 0xFF)};
	}
	public void shutdown() {
		sendAndReceiveSocket.close();
		System.exit(1);
	}
	
	
	/**
	 * 
	 * @param reqType
	 * @param filename
	 * @param mode
	 * @return
	 */
	private byte[] formatRequest(byte[] reqType, String filename, String mode) {
		byte[] request = new byte[reqType.length + filename.length() + mode.length() + 2]; // +2 for the zero byte after filename and after mode.
		byte[] filenameData = filename.getBytes();
		byte[] modeData = mode.toLowerCase().getBytes();
		int i, j, k;
		
		for (i = 0; i < reqType.length; i++) {
			request[i] = reqType[i];
		}

		for (j = 0; j < filenameData.length; j++) {
			request[i + j] = filenameData[j];
		}
		request[i + j] = 0; // zero byte after filename.
		j++; 
		for (k = 0; k < modeData.length; k++) {
			request[i + j + k] = modeData[k];
		}
		
		request[i + j + k] = 0; // final zero byte.

		return request;
	}
	
	private byte[] formatData(byte[] data, int blockNumber) {
		
		byte[] formatted = new byte[data.length + 4]; // +4 for opcode and datablock number (2 bytes each)
		byte[] blockNumData = convertBlockNumberByteArr(blockNumber);
		int i;
		
		// opcode
		formatted[0] = dataOP[0];
		formatted[1] = dataOP[1];
		// blockNumber
		formatted[2] = blockNumData[0];
		formatted[3] = blockNumData[1];
		
		for (i = 0; i < data.length; i++) {
			formatted[i + 4] = data[i];
		}

		return formatted;
	}
	
	public static void main(String args[]) {
		Client c = new Client();
		
		System.out.println("Hello! Please type which mode to run in; normal or test: (n/t)");
		Scanner s = new Scanner(System.in);
		String mode = s.nextLine().toLowerCase();
		
		if (mode.equals("n") || mode.equals("normal")) {
			c.setTestMode(false);
		}
		else if (mode.equals("t") || mode.equals("test")) {
			c.setTestMode(true);
		}
		
		System.out.println("Now choose whether you would like to run in quiet or verbose mode (q/v):");
		String response = s.nextLine();
		if (response.equals("q")) {
			TFTPInfoPrinter.setVerboseMode(false);
		}
		else if (response.equals("n")) {
			TFTPInfoPrinter.setVerboseMode(true);
		}
		
		while (true) {
			System.out.println("Please enter in the file name (or \"shutdown\" to exit):");
			String fileName = s.nextLine();
			if (fileName.equals("shutdown")) break;
			
			System.out.println("Read or Write? (r/w)");
			String action = s.nextLine().toLowerCase();
			
			try {
				if (action.equals("r") || action.equals("read")) {
					c.readFromServer(fileName, mode);
					System.out.println("Transfer complete");
				}
				else if (action.equals("w") || action.equals("write")) {
					c.writeToServer(fileName, mode);
					System.out.println("Transfer complete");
				}
				else {
					System.out.println("Invalid command");
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		s.close();
		c.shutdown();
	}

}