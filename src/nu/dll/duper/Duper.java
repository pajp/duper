package nu.dll.duper;

import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.security.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.twmacinta.util.*;

public class Duper {
	
    Set beenThere = new HashSet();
    Map sizeFileMap = new HashMap();
    Map fileChecksumMap = new HashMap();
    Map checksumFileMap = new HashMap();
    List dupes = new LinkedListHack();
    List roots;
    MD5 md = null;
    MessageDigest stdmd = null;
	
    long minimumSize = 0;
    int filesProcessed = 0;
    int filesSkipped = 0;
    int directoriesProcessed = 0;
    boolean pedantic = false;
	
    boolean aborted = false;
	
    ProgressListener listener = null;
	
    long startTime, endTime;
	
    int dupeCount = 0;
	
    long totalBytes = 0;
	
    long totalBytesChecksummed = 0;
    int totalFilesChecksummed = 0;
	
    int blocksize = Integer.getInteger("duper.block-size", 1024*1024).intValue();
    byte[] md5buffer = new byte[blocksize];
	
    List folderIgnorePatterns = new LinkedList();
    List fileIgnorePatterns = new LinkedList();
	
    boolean ignoreHidden = Boolean.getBoolean("duper.ignore-hidden");
    boolean ignoreFileNotFound = true;
	
    boolean saveChecksums = true;
	
    boolean debug = Boolean.getBoolean("duper.debug");
	
    boolean stdmd5 = false;
	
    static File diskCacheFile = new File(System.getProperty("user.home"), ".duper.cache.gz");
    Writer cacheWriter = null;
    OutputStream cacheStream = null;
	
    public interface ProgressListener {
	public void cacheLoadStart();
	public void cacheLoadStop();
	public void scanStart();
	public void scanStop(boolean aborted);
	public void enteringDirectory(File f);
	public void ioError(File f, IOException e);
    }
	
    static class LinkedListHack extends LinkedList {
	public boolean contains(Object o) {
	    for (Iterator i = iterator();i.hasNext();) {
		if (o == i.next()) return true;
	    }
	    return false;
	}
    }
	
    public void setProgressListener(ProgressListener listener) {
	this.listener = listener;
    }
	
    public void addFileIgnorePattern(String s) {
	fileIgnorePatterns.add(Pattern.compile(s));
    }
    
    // not implemented yet
    public void addFolderIgnorePattern(String s) {
	folderIgnorePatterns.add(s);
    }
	
    public Duper(List roots, boolean checksumDiskCache, boolean defaultMd5) {
	this.roots = roots;
	this.stdmd5 = defaultMd5;
	saveChecksums = checksumDiskCache;
	if (debug) dprintln("MD5 disk cache enabled: " + saveChecksums);
	if (defaultMd5) {
	    try {
		stdmd = MessageDigest.getInstance("MD5");
	    } catch (NoSuchAlgorithmException ex1) {
		throw new RuntimeException(ex1.toString());
	    }
	} else {
	    md = new MD5();
	}
		
    }
	
    public void processRoots() throws IOException {
	startTime = System.currentTimeMillis();
	endTime = 0;
		
	if (listener != null) listener.scanStart();
		
	if (saveChecksums) {
	    try {
		if (diskCacheFile.exists()) {
		    if (debug) dprintln("Loading MD5 cache from " + diskCacheFile);
					
		    if (listener != null) {
			listener.cacheLoadStart();
		    }
					
		    BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(diskCacheFile)), "utf-8"));
		    String row, lastrow = null;
		    int count=0;
		    while ((row = reader.readLine()) != null) {
			String nextrow = reader.readLine();
			if (row != null && nextrow != null) {
			    String filename = row;
			    File file = new File(filename);
			    String md5 = nextrow;
			    fileChecksumMap.put(file, md5);
			    Collection sumfiles = (Collection) checksumFileMap.get(md5);
			    if (sumfiles != null) {
				sumfiles.add(new File(filename));
			    } else {
				sumfiles = new HashSet();
				sumfiles.add(file);
				checksumFileMap.put(md5, sumfiles);
			    }
			    if (debug) {
				dprintln("Loaded cached MD5 sum for " + file);
			    }
			}
			count++;
		    }
		    if (debug) dprintln("cached md5 loaded for " + count + " files");
		    reader.close();
		}
				
				
		cacheStream = new GZIPOutputStream(new FileOutputStream(diskCacheFile, true));
		cacheWriter = new OutputStreamWriter(cacheStream, "utf-8");
				
		if (listener != null) {
		    listener.cacheLoadStop();
		}
				
	    } catch (IOException ex1) {
		throw new RuntimeException("Error loading MD5 cache:" + ex1.toString());
	    }
	}
		
	for (Iterator i = roots.iterator();!aborted && i.hasNext();) {
	    processDirectory((File) i.next());
	}
	sparehack();
	if (!aborted && pedantic) {
	    pedantic();
	}
	endTime = System.currentTimeMillis();
	if (listener != null) listener.scanStop(aborted);
		
	if (cacheWriter != null) {
	    cacheWriter.close();
	    cacheStream.close();
	}
		
    }
	
    private void sparehack() {
	for (Iterator i = dupes.iterator();i.hasNext();) {
	    Collection l = (Collection) i.next();
	    if (l.size() == 1) {
		i.remove();
	    } else {
		dupeCount += l.size()-1;
	    }
			
	}
    }
	
    public long getDuration() {
	if (endTime == 0) {
	    return System.currentTimeMillis() - startTime;
	} else {
	    return endTime - startTime;
	}
    }
	
    void pedantic() throws IOException {
	for (Iterator i = dupes.iterator();i.hasNext();) {
	    List files = (List) i.next();
	    if (files.size() == 2) {
		if (!pedanticCompareFiles((File) files.get(0), (File) files.get(1))) {
		    i.remove();
		}
	    } else {
		Object[] filesArr = files.toArray();
		List toRemove = new LinkedList();
		for (int j=0; j < filesArr.length; j++) {
		    for (int k=j+1; k < filesArr.length; k++) {
			if (!pedanticCompareFiles((File) filesArr[j], (File) filesArr[k])) {
			    toRemove.add(filesArr[k]); // XXX help, what to do? when/which to remove?
			}
		    }
		}
		files.removeAll(toRemove);
		throw new RuntimeException("Size: " + files.size() + ": Not supported yet.");
	    }
	}
    }
	
    boolean pedanticCompareFiles(File a, File b) throws IOException {
	if (debug) dprintln("pedantic: " + a + " vs " + b);
	FileInputStream stream_a = new FileInputStream(a);
	FileInputStream stream_b = new FileInputStream(b);
	FileChannel chan_a = stream_a.getChannel();
	FileChannel chan_b = stream_b.getChannel();
	ByteBuffer buf_a = chan_a.map(FileChannel.MapMode.READ_ONLY, 0, chan_a.size());
	ByteBuffer buf_b = chan_b.map(FileChannel.MapMode.READ_ONLY, 0, chan_b.size());
	boolean equal = true;
	while (equal && buf_a.hasRemaining() && buf_b.hasRemaining()) {
	    if (buf_a.get() != buf_b.get()) {
		equal = false;
	    }
	}
	if (buf_a.hasRemaining() || buf_b.hasRemaining()) {
	    equal = false;
	}
	if (debug) dprintln("pedantic check failed");
	chan_a.close();
	chan_b.close();
	stream_a.close();
	stream_b.close();
	return equal;
    }
	
    boolean isRoot(File file) {
	File[] _roots = File.listRoots();
	for (int i=0; i < _roots.length; i++) {
	    if (file.equals(_roots[i])) return true;
	}
	return false;
    }
	
    void processDirectory(File dir) throws IOException {
	if (!dir.isDirectory())
	    throw new IOException(dir.getAbsolutePath() + " isn't a directory");
	if (ignoreHidden && dir.isHidden() && !isRoot(dir)) {
	    if (debug) dprintln("Skipping hidden directory " + dir);
	    return;
	}
		
	if (listener != null) {
	    listener.enteringDirectory(dir);
	}
	File[] files = dir.listFiles();
		
	for (int i=0; !aborted && files != null && i < files.length; i++) {
	    if (files[i].isDirectory()) {
		processDirectory(files[i]);
	    } else {
		processFile(files[i]);
	    }
	}
	directoriesProcessed++;
    }
	
    void abort() {
	aborted = true;
    }
	
	
    void processFile(File file) throws IOException {
	Long size = new Long(file.length());
	if (size.longValue() < minimumSize) {
	    if (debug) {
		dprintln("Skipping small file of size " + size);
	    }
	    filesSkipped++;
	    return;
	}
	for (Iterator i = fileIgnorePatterns.iterator();i.hasNext();) {
	    Pattern pattern = (Pattern) i.next();
	    if (pattern.matcher(file.getName()).matches()) {
		filesSkipped++;
		if (debug) {
		    dprintln("Ignoring file " + file + " matching " + pattern.pattern());
		}
		return;
	    }
	}
	totalBytes += size.longValue();
	if (!sizeFileMap.containsKey(size)) {
	    List files = new LinkedList();
	    files.add(file);
	    sizeFileMap.put(size, files);
	    filesProcessed++;
	    return;
	}
	List files = (List) sizeFileMap.get(size);
	files.add(file);
	//dprintln("Size match (" + size + "): " + files);
	compareFiles(files);
	filesProcessed++;
    }
	
    boolean equals(byte[] a, byte[] b) {
	return MessageDigest.isEqual(a, b);
    }
	
    void dprintln(String s) {
	System.out.println(s);
    }
	
    void compareFiles(List files) throws IOException {
	for (Iterator i = files.iterator(); i.hasNext();) {
	    File f = (File) i.next();
	    String md5string;
	    try {
		md5string = getMD5(f);
	    } catch (FileNotFoundException ex1) {
		listener.ioError(f, ex1);
		if (ignoreFileNotFound) {
		    continue;
		} else {
		    throw ex1;
		}
	    }
			
	    Collection sumfiles = (Collection) checksumFileMap.get(md5string);
	    if (sumfiles == null) {
		sumfiles = new HashSet();
		checksumFileMap.put(md5string, sumfiles);
	    } else {
		if (!dupes.contains(sumfiles)) dupes.add(sumfiles);
		//dprintln("(dupe!)");
	    }
	    if (!sumfiles.contains(f)) sumfiles.add(f);
	}
    }
	
    String hexstring(byte[] data) {
	StringBuffer buf = new StringBuffer();
	for (int i=0; i < data.length; i++) {
	    if (((int)  data[i] & 0xff) < 0x10) buf.append("0");
	    buf.append(Long.toString((int) data[i] & 0xff, 16));
	}
	return buf.toString();
    }
	
	
    private byte[] calcMD5_stream(InputStream fis) throws IOException {
	md5init();
	int bytesRead;
	while ((bytesRead = fis.read(md5buffer)) != -1) {
	    md5update(md5buffer, 0, bytesRead);
	    totalBytesChecksummed += bytesRead;
	}
	return md5digest();
    }
	
    private byte[] calcMD5_mmap(FileInputStream fis) throws IOException {
	FileChannel fc = fis.getChannel();
	int size = (int) fc.size();
	MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, size);
	md5init();
	md5update(buf, 0, size);
	byte[] sum = md5digest();
	fc.close();
	totalBytesChecksummed += size;
	return sum;
    }
	
    void md5update(byte[] data, int offset, int len) {
	md5update(ByteBuffer.wrap(data), offset, len);
    }
	
	
    void md5update(ByteBuffer data, int offset, int len) {
	if (stdmd5) {
	    if (data.hasArray()) {
		stdmd.update(data.array(), offset, len);
	    } else {
		byte[] tmpbuf = new byte[len-offset];
		data.position(offset);
		data.get(tmpbuf);
		stdmd.update(tmpbuf, 0, len);
	    }
	} else {
	    byte[] temparray = new byte[len];
	    data.get(temparray, offset, len);
	    md.Update(temparray, offset, len);
	}
    }
	
    byte[] md5digest() {
	if (stdmd5) {
	    return stdmd.digest();
	} else {
	    return md.Final();
	}
    }
	
    void md5init() {
	if (stdmd5) {
	    stdmd.reset();
	} else {
	    md.Init();
	}
    }
	
    boolean useMmapIO = !Boolean.getBoolean("duper.disable-mmap");
    boolean aggressiveGC = Boolean.getBoolean("duper.agressive-gc");
    Set md5debugonce = new HashSet();
    public String getMD5(File file) throws IOException {
	String filename = file.getAbsolutePath();
	if (fileChecksumMap.containsKey(file)) {
	    String md5 = (String) fileChecksumMap.get(file);
	    if (debug && !md5debugonce.contains(file)) {
		dprintln(file + " MD5 (cached): " + md5);
		md5debugonce.add(file);
	    }
	    return md5;
	}
	FileInputStream fis = new FileInputStream(file);
	byte[] sum;
		
	if (useMmapIO) {
	    sum = calcMD5_mmap(fis);
	} else {
	    sum = calcMD5_stream(fis);
	}
	// on windows, file handles remain open if I just close the channel
	fis.close();
	if (aggressiveGC) System.gc();
	totalFilesChecksummed++;
		
	String sumstr = hexstring(sum);
	if (saveChecksums) {
	    if (debug) {
		dprintln("writing md5 for " + filename + " to cache.");
	    }
	    cacheWriter.write(filename+"\n"+sumstr+"\n");
	}
		
	fileChecksumMap.put(file, sumstr);
	if (debug && !md5debugonce.contains(file)) {
	    dprintln(file + " MD5 (computed): " + sumstr);
	    md5debugonce.add(file);
	}
	return sumstr;
    }
	
    public void dispose() {
    }
	
    public List getDupes() {
	return dupes;
    }
	
}
