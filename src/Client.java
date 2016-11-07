import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Scanner;

public class Client {
	private static final int bufferSize = 516;
	private static final int blockSize = 512;
	private boolean testMode = false;
	private int wellKnownPort;
	private DatagramPacket sendPacket, receivePacket;
	private DatagramSocket sendAndReceiveSocket;

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
		RequestPacket p = new RequestPacket(reqType, filename, mode);
		byte[] message = p.encode();
		
		DatagramPacket request = new DatagramPacket(message, message.length, InetAddress.getLocalHost(), wellKnownPort);
		
		try {
			sendAndReceiveSocket.send(request);
		} catch (IOException e) {
			e.printStackTrace();
		}
		TFTPInfoPrinter.printSent(request);
	}
	
	public void readFromServer(String filename, String mode) throws IOException{		
		System.out.println("Initiating read request with file " + filename);
		
		
		byte[] receivedData;
		byte[] receivedOpcode;
		int currentBlockNumber = 1;
		
		if(new File("ClientFiles/" + filename).exists()){
			System.err.println('"' + filename + '"' + " already exists on Client.");
			return;
		}
		BufferedOutputStream out = null;
		
		try {
			out = new BufferedOutputStream(new FileOutputStream("ClientFiles/" + filename));
		} catch (IOException e) {
			if (e.getMessage().contains("(Access is denied)"));
			System.err.println("Access to ClientFiles folder was denied");
			return;
		}
		
		
		sendRequest(RequestPacket.readOpcode, filename, mode);
		
		while (true) {
			receivedData = new byte[bufferSize];
			receivePacket = new DatagramPacket(receivedData, receivedData.length);
			// receive block
			try{
				sendAndReceiveSocket.receive(receivePacket);
			} catch(IOException e)
			{
							
			}
			TFTPInfoPrinter.printReceived(receivePacket);
			
			// validate packet
			receivedData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
			receivedOpcode = Arrays.copyOf(receivedData, 2);

			
			if (Arrays.equals(receivedOpcode, ErrorPacket.opcode)){
				ErrorPacket ep = new ErrorPacket(receivedData);
				System.err.println(ep.getErrorMessage());
				// Handle error.
				
				// File not found on server
				if (ep.getErrorCode() == 1){
					
				}
				// Access denied on server
				else if (ep.getErrorCode() == 2) {
					
				}
				out.close();
				return;
			}
			// The received packet should be an DATA packet at this point, and this have the Opcode defined in dataOP.
			// If it is not an error packet or an DATA packet, something happened (these cases are in later iterations).
			else if (!Arrays.equals(receivedOpcode, DataPacket.opcode)) {
				// Do nothing special for Iteration 2.
			}
			
			DataPacket dp = new DataPacket(receivedData);
			
			int blockNum = dp.getBlockNum();
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
			byte[] dataBlock = dp.getDataBlock();
			
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
				try {
					out.close();
				} catch (IOException e2) {
					
				}
				return;
			}

			// Send ack back
			AckPacket ap = new AckPacket(blockNum);
			
			// Initial request was sent to wellKnownPort, but steady state file transfer should happen on another port.
			sendPacket = new DatagramPacket(ap.encode(), ap.encode().length, InetAddress.getLocalHost(), receivePacket.getPort());
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
		BufferedInputStream in = null;
		byte[] receivedData;
		byte[] receivedOpcode;
		
		
		
		try {
			// It's a full path
			if (filename.contains("\\") || filename.contains("/")) {
				
				if(!(new File(filename).exists())){
					System.err.println(filename + " does not exist on Client.");
					return;
				}
				
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
				

				if(!(new File("ClientFiles/" + filename).exists())){
					System.err.println(filename + " does not exist on Client.");
					return;
				}
				
				in = new BufferedInputStream(new FileInputStream("ClientFiles/" + filename));
				
				
			}
		} catch (IOException e) {
			// Don't bother sending the server any error packets as the request hasn't been sent and server doesn't need to know
			// Print out error information/handle error.
			
			if (e.getMessage().contains("(Access is denied)")) {
				System.err.println("Cound not read " + filename + " on Client");
			}
			else {
				e.printStackTrace();
			}
			return;
		}
		
		
		sendRequest(RequestPacket.writeOpcode, filename, mode);
		
		while (true) {
			// receive ACK from previous dataBlock
			byte[] data = new byte[bufferSize];
			receivePacket = new DatagramPacket(data, data.length);
			try
			{
				sendAndReceiveSocket.receive(receivePacket);
			} catch(IOException e)
			{
				e.printStackTrace();
			}			
			TFTPInfoPrinter.printReceived(receivePacket);
			
			receivedData = Arrays.copyOf(receivePacket.getData(), receivePacket.getLength());
			receivedOpcode = Arrays.copyOf(receivedData, 2);
			
			if (Arrays.equals(receivedOpcode, ErrorPacket.opcode)){
				ErrorPacket ep = new ErrorPacket(receivedData);
				System.err.println(ep.getErrorMessage());
				// Access denied, can't write to server
				if (ep.getErrorCode() == 2) {
					
				}
				// File already exits (on server)
				else if (ep.getErrorCode() == 6) {
					
				}
				
				in.close();
				return;
			}
			// The received packet should be an ACK packet at this point, and this have the Opcode defined in ackOP.
			// If it is not an error packet or an ACK packet, something happened (these cases are in later iterations).
			else if (!Arrays.equals(receivedOpcode, AckPacket.opcode)) {
				// Do nothing special for Iteration 2.
			}
			
			AckPacket ap = new AckPacket(receivedData);
			// need block number
			int blockNum = ap.getBlockNum();
			
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
			
			byte[] dataBlock = new byte[blockSize];
			
			// Resize dataBlock to total bytes read
			int bytesRead = in.read(dataBlock);
			if (bytesRead == -1) bytesRead = 0;
			dataBlock = Arrays.copyOf(dataBlock, bytesRead);
			
			DataPacket dp = new DataPacket(currentBlockNumber, dataBlock);
			byte[] sendData = dp.encode();
			// Initial request was sent to wellKnownPort, but steady state file transfer should happen on another port.
			sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getLocalHost(), receivePacket.getPort());
			sendAndReceiveSocket.send(sendPacket);
			
			TFTPInfoPrinter.printSent(sendPacket);
			
			if (bytesRead < 512) break;
		}
		
		in.close();
	}
	
	
	public void shutdown() {
		sendAndReceiveSocket.close();
		System.exit(1);
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
					//check if a file with that name exists on the client side
					c.readFromServer(fileName, "octet");
					System.out.println("Transfer complete");

				}
				else if (action.equals("w") || action.equals("write")) {
					c.writeToServer(fileName, "octet");
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
