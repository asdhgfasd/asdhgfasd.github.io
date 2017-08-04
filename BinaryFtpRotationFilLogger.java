package com.logpresso.sdk.binary;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import sun.misc.IOUtils;

import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.araqne.log.api.AbstractLogger;
import org.araqne.log.api.Log;
import org.araqne.log.api.LogPipe;
import org.araqne.log.api.Logger;
import org.araqne.log.api.LoggerFactory;
import org.araqne.log.api.LoggerSpecification;
import org.araqne.log.api.LoggerStopReason;
import org.araqne.log.api.MultilineLogExtractor;
import org.araqne.log.api.Reconfigurable;
import org.araqne.log.api.SimpleLog;
import org.logpresso.ftp.FtpProfile;
import org.logpresso.ftp.FtpProfileRegistry;
import org.logpresso.ftp.FtpProfileRegistryListener;
import org.logpresso.ftp.FtpRateLimitInputStream;
import org.logpresso.ftp.FtpRateLimitService;
import org.logpresso.ftp.FtpUtils;

public class BinaryFtpRotationFileLogger extends AbstractLogger implements LogPipe, Reconfigurable, FtpProfileRegistryListener {
	static private final int  BASELENGTH   = 128;
    static private final int  LOOKUPLENGTH = 16;
    static final private byte [] hexNumberTable    = new byte[BASELENGTH];
    static final private char [] lookUpHexAlphabet = new char[LOOKUPLENGTH];


    static {
        for (int i = 0; i < BASELENGTH; i++ ) {
            hexNumberTable[i] = -1;
        }
        for ( int i = '9'; i >= '0'; i--) {
            hexNumberTable[i] = (byte) (i-'0');
        }
        for ( int i = 'F'; i>= 'A'; i--) {
            hexNumberTable[i] = (byte) ( i-'A' + 10 );
        }
        for ( int i = 'f'; i>= 'a'; i--) {
           hexNumberTable[i] = (byte) ( i-'a' + 10 );
        }

        for(int i = 0; i<10; i++ ) {
            lookUpHexAlphabet[i] = (char)('0'+i);
        }
        for(int i = 10; i<=15; i++ ) {
            lookUpHexAlphabet[i] = (char)('A'+i -10);
        }
    }
    
	private final org.slf4j.Logger slog = org.slf4j.LoggerFactory.getLogger(BinaryFtpRotationFileLogger.class);
	private final File dataDir;
	private String filePath;
//	private final AtomicLong lastPosition = new AtomicLong(0);

	// real destination file after follow symlinks
	private String realFilePath;

	private FtpProfileRegistry profileRegistry;
	private FtpRateLimitService limitService;

	private String charset;

	private MultilineLogExtractor extractor;

	private FTPClient client;

	public BinaryFtpRotationFileLogger(LoggerSpecification spec, LoggerFactory factory, FtpProfileRegistry profileRegistry,
			FtpRateLimitService limitService) {
		super(spec, factory);
		this.profileRegistry = profileRegistry;
		this.limitService = limitService;

		this.dataDir = new File(System.getProperty("araqne.data.dir"), "binary-ftp");
		this.dataDir.mkdirs();

		init(getConfigs());
	}

	private void init(Map<String, String> c) {		
		try{
			this.filePath = c.get("file_path");
			
            // optional
            String datePatternRegex = c.get("date_pattern");
            if (datePatternRegex != null) {
                extractor.setDateMatcher(Pattern.compile(datePatternRegex).matcher(""));
            }

            // optional
            String dateLocale = c.get("date_locale");
            if (dateLocale == null)
                dateLocale = "en";

            // optional
            String dateFormatString = c.get("date_format");
            String timeZone = c.get("timezone");
            if (dateFormatString != null)
                extractor.setDateFormat(new SimpleDateFormat(dateFormatString, new Locale(dateLocale)), timeZone);

            // optional
            String beginRegex = c.get("begin_regex");
            if (beginRegex != null)
                extractor.setBeginMatcher(Pattern.compile(beginRegex).matcher(""));

            String endRegex = c.get("end_regex");
            if (endRegex != null)
                extractor.setEndMatcher(Pattern.compile(endRegex).matcher(""));

            // optional
            charset = c.get("charset");
            if (charset == null)
                charset = "utf-8";

            extractor.setCharset(charset);

		} catch (Throwable e){
			slog.error("init error : message [{}] ", e);
		}
	}

	@Override
	public void onUpdated(FtpProfile oldProfile, FtpProfile newProfile) {
		if (newProfile.getName().equals(getConfigs().get("ftp_profile"))) {
			if (!oldProfile.getHost().equals(newProfile.getHost()) || oldProfile.getPort() != newProfile.getPort()) {
				slog.info("binary ftp: ftp-rotation [{}] turn back to the initial state", getFullName());
				setStates(new HashMap<String, Object>());
			}
		}
	}

	@Override
	public void onConfigChange(Map<String, String> oldConfigs, Map<String, String> newConfigs) {
		init(newConfigs);
		if (!oldConfigs.get("ftp_profile").equals(newConfigs.get("ftp_profile"))
				|| !oldConfigs.get("file_path").equals(newConfigs.get("file_path")))
			setStates(new HashMap<String, Object>());
	}

	@Override
	protected void onStop(LoggerStopReason reason) {
		try {
			slog.debug("binary ftp: force close client socket of logger [{}]", getFullName());
			client.disconnect();
		} catch (Throwable t) {
		}
	}

	@Override
	public void onLog(Logger logger, Log log) {
		write(log);
	}

	@Override
	public void onLogBatch(Logger logger, Log[] logs) {
		writeBatch(logs);
	}

	@Override
	protected void runOnce() {
		FileInfo currentState = null;
		Map<String, Object> state = getStates();
		String firstLine = (String) state.get("first_line");
		long lastPos = state.get("last_position") != null ? Long.valueOf(state.get("last_position").toString()) : 0L;
		long fileLength = state.get("file_length") != null ? Long.valueOf(state.get("file_length").toString()) : 0L;
		FileInfo lastState = new FileInfo(firstLine, lastPos, fileLength);

		AtomicLong lastPosition = new AtomicLong(0);

		InputStream is = null;
		client = null;
		boolean opened = false;

		try {
			String profileName = getConfigs().get("ftp_profile");
			FtpProfile profile = profileRegistry.getProfile(profileName);
			if (profile == null)
				throw new IllegalStateException("ftp profile not found: " + profileName);

			slog.debug("binary ftp: ftp-rotation [{}] trying to connect [{}]", getFullName(), profileName);

			client = new FTPClient();
			FtpUtils.connect(client, profile);
			
			if(client == null){
				slog.info("client is nullPoint Exception !!!");
			}

			// compare
			currentState = getCurrentInfo(client);
			if (currentState == null)
				return;

			long offset = 0;
			
			if (lastState != null) {
				offset = lastState.lastPosition;	
			}
			lastPosition.set(offset);
			
			if (offset >= currentState.fileLength) {
				slog.debug("binary ftp: ftp-rotation [{}] no more data from file [{}]", getFullName(), realFilePath);
				return;
			}

			try {
                client.disconnect();
            } catch (Throwable var34) {
            	slog.warn("logpresso ftp: unexpected exception while closing client after readFirstLine from [" + getFullName() + "]: " + var34.getMessage());
                slog.debug("logpresso ftp: exception detail: ", var34);           
            } 

			FtpUtils.connect(client, profile);
			if (offset != 0)
				client.setRestartOffset(offset);

			String limitProfileName = getConfigs().get("rate_limit");

			slog.debug("binary ftp: ftp-rotation [{}] loading file [{}]", getFullName(), realFilePath);
			
			// dubug
			InputStream is1 = client.retrieveFileStream(realFilePath);
			
			opened = true;

			if (limitProfileName != null){
				is1 = new BufferedInputStream(new FtpRateLimitInputStream(is1, limitProfileName, limitService));
			}else{		
				is1 = new BufferedInputStream(is1);
			}
			byte[] temp = inputStreamToByteArray(is1);
			
			int tempLen = temp.length+1;
			int bufferByte = 2048;
			
			if(offset <= (currentState.fileLength +1)){
				String firstLineTemp = "";
				List<Map<String, Object>> resList = new ArrayList<Map<String, Object>>();
				int arrSize = 0;
				try{
					
					for(int i = 0 ; i<10; i ++){
						Map<String, Object> dummyData = new HashMap<String, Object>();
						dummyData.put("line", (Object) "@");
						write(new SimpleLog(new Date(), this.getFullName(), dummyData));
					}
					
					byte[] temp2 = new byte[bufferByte];
					for(int i = 0; i< tempLen; i=i+bufferByte){
						temp2 = Arrays.copyOfRange(temp, i, i+bufferByte  );
						String line = byteToHex(temp2);
						Map<String, Object> output = new HashMap<String, Object>();
						output.put("line", (Object) line);
						resList.add(output);
						arrSize++;
						lastPosition.addAndGet((long) bufferByte);
						
						if( i == 0){
							firstLineTemp = line;
							updateState(firstLineTemp, tempLen, lastPosition.get());
						}
					}
					
					for(int i = arrSize -1 ;i>=0 ;i-- ){
						write(new SimpleLog(new Date(), this.getFullName(), resList.get(i)));
					}
					resList = null;

					updateState(firstLineTemp, tempLen, lastPosition.get());

                    } catch (Throwable t){
                        slog.error("byte array to String exchange error ! ", t);
                    }
				setTemporaryFailure(null);
			}
			
		} catch (Throwable t) {
			setTemporaryFailure(t);
			slog.error("binary ftp: ftp rotation logger [" + getFullName() + "] cannot read file", t);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					slog.error("error !!! [{}]",e);
				}
			}
			
			if (currentState != null) {
				updateState(currentState.firstLine, currentState.fileLength, lastPosition.get());
				slog.debug("binary ftp: after normal end position [{}]", lastPosition.get());
			}

			try {
				if (opened && !client.completePendingCommand()) {
					slog.error("binary ftp: logger [{}] failed to complete pending command", getFullName());
				}
			} catch (IOException e) {
				slog.error("binary ftp: logger [{}] cannot complete pending command errorName : [{}]", getFullName(), e.getMessage());
			} finally {
				try{
					FtpUtils.disconnect(client);
					client = null;					
				} catch (Throwable t){
					slog.error("Ftp client close failed [{}]", t);
				}
			}
		}
	}

	public void updateState(String firstLine, long fileLength, long lastPosition) {
		Map<String, Object> m = new HashMap<String, Object>();
		m.put("first_line", firstLine);
		m.put("last_position", lastPosition);
		m.put("file_length", fileLength);
		setStates(m);
	}

	private String readFirstLine(InputStream is) {
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(is, charset));
			return br.readLine();
		} catch (Throwable t) {
			slog.error("binary ftp: cannot read first line, ftp-rotation logger [" + getFullName() + "]", t);
			return null;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
				}
			}
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
		}
	}

	protected File getLastLogFile() {
		return new File(dataDir, "ftp-rotation-" + getName() + ".lastlog");
	}

	private FileInfo getCurrentInfo(FTPClient client) throws IOException {
		long fileSize = 0;
		try{
			this.realFilePath = filePath;
			if(filePath.length() <= 0){
				slog.info("binary ftp: ===== FilePath : [{}]", realFilePath);
				throw new Exception("FilePath is null!!!");
			}
		}catch(Exception e){
			slog.info("binary ftp: FilePath is not found [{}]",e);
		}

		boolean found = false;
		while (true) {
			slog.debug("binary ftp: checking path [{}]", realFilePath);
			FTPFile f = findFile(client, realFilePath);
			if (f == null)
				break;

			if (f.isFile()) {
				fileSize = f.getSize();
				found = true;
				slog.debug("binary ftp: found path [{}] size [{}]", realFilePath, fileSize);
				break;
			} 
			if (f.isSymbolicLink()) {
				break;
			}	
			
			int p = this.realFilePath.lastIndexOf('/');
			if (p < 0){
				break;
			}

			String oldPath = realFilePath;
			realFilePath = realFilePath.substring(0, p + 1) + f.getLink();
			if (oldPath.equals(realFilePath)){
				break;
			}	
			slog.debug("binary ftp: symlink found path [{}] link [{}]", realFilePath, f.getLink());
		}
		if (!found) {
			slog.debug("binary ftp: rotation logger [{}] file [{}] not found", getFullName(), filePath);
			return null;
		}else{
			String firstLine = null;
			try {
				InputStream is = client.retrieveFileStream(realFilePath);
				
				//debug
				firstLine = readFirstLine(is);
			} catch (IOException e) {
				slog.error("binary ftp: logger [{}] cannot complete pending command", getFullName());
			} finally {
				try {
					if (!client.completePendingCommand()) {
						slog.debug("binary ftp: logger [{}] failed to complete pending command, reply [{}]", getFullName(),
								client.getReplyString());
					}
				} catch (IOException e) {
					if(e instanceof FTPConnectionClosedException) {
                        slog.debug("logpresso ftp: logger [{}] cannot complete pending command", getFullName());
                        slog.debug("logpresso ftp: exception detail: ", e);
	                }else{
                        slog.error("logpresso ftp: logger [{}] cannot complete pending command: [{}]", getFullName(), e.getMessage());
                        slog.debug("logpresso ftp: exception detail: ", e);
	                }
				}
			}
			return new FileInfo(firstLine, 0, fileSize);
		}
	}

	private FTPFile findFile(FTPClient client, String path) throws IOException {
		FTPFile[] files = client.listFiles(path);
		if (files == null || files.length == 0)
			return null;

		return files[0];
	}

	private class FileInfo {
		private String firstLine;
		private long lastPosition;
		private long fileLength;

		public FileInfo(String firstLine, long lastPosition, long lastLength) {
			this.firstLine = firstLine;
			this.lastPosition = lastPosition;
			this.fileLength = lastLength;
		}
	}
	
	private byte[] inputStreamToByteArray(InputStream is){
		byte[] resBytes = null;
		    ByteArrayOutputStream bos = new ByteArrayOutputStream();
		     
		    byte[] buffer = new byte[1025];
		    int read = -1;
		    try {
		        while ( (read = is.read(buffer)) != -1 ) {
		           bos.write(buffer, 0, read);
		        }   
		        resBytes = bos.toByteArray();
		        bos.close();
		    }
		    catch (IOException e) {
		        slog.error("inputStream to byte array convert error ", e);
		    }
		    return resBytes;
	}
	
	private String byteToHex(byte[] binaryData){
		if(binaryData == null){
			return null;
		}
		int lengthData   = binaryData.length;
        int lengthEncode = lengthData * 2;
        char[] encodedData = new char[lengthEncode];
        int temp;
        for (int i = 0; i < lengthData; i++) {
            temp = binaryData[i];
            if (temp < 0)
                temp += 256;
            encodedData[i*2] = lookUpHexAlphabet[temp >> 4];
            encodedData[i*2+1] = lookUpHexAlphabet[temp & 0xf];
        }
        return new String(encodedData);
	}
}

