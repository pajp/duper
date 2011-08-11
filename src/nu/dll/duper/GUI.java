package nu.dll.duper;

import org.eclipse.swt.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.browser.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.program.Program;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

import java.io.File;
import java.io.IOException;

import java.util.Comparator;
import java.util.Collections;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.Iterator;

import java.util.Map;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;

import java.util.prefs.Preferences;
import java.util.prefs.BackingStoreException;

import java.text.DateFormat;


/**
   2004-12-12: I am writing this sitting in a car, on my way home to
   Katrineholm from Stockholm. In this very moment, I am a little bit
   shaky. A car accident had happened where a car got a flat tire, lost
   control, and got hit from behind by another car.  There seemed to be
   no serious injuries, but everyone there was very chocked. There were
   pieces of glass, plastic and metal spread over the road, and the
   roadside fence was missing or broken for several meters. My brother
   and our driver went to check if they needed any help, and I stayed
   by our car, waving approaching cars that the road was blocked. It was
   a one-laned way, so there wasn't much space, but we were forced to
   turn around and go in the wrong direction for a couple of
   kilometers. Anyway. It was scary. Reminds you that these things
   actually happen, and that it could equally well be our car that was
   involved. 
 
 
*/

public class GUI implements Duper.ProgressListener {
    Display display;
    Shell shell;
    List rootList;
    List consoleList;
    Table dupeTable;
	
    Button startScanBtn;
    Button stopScanBtn;
    Text minSizeText;
    Button enableCacheBtn;
    Button enableMmapBtn;
    Button enableAggressiveGcBtn;
    Button enableOptimizedMd5Btn;
    Combo selectStrategyCombo;
	
    Duper duper = null;
    Collection excludeFiles = new LinkedList();
	
    File homeDir = new File(System.getProperty("user.home"));
    File rootListFile = new File(homeDir, ".duper.roots");
	
    Exception exception;
	
    Color white;
    Color pyjamasColor;
	
    boolean isWindows = System.getProperty("os.name").startsWith("Windows");
    
    Thread scanThread;
	
    Preferences prefs = Preferences.userNodeForPackage(GUI.class);
	
    boolean debug = Boolean.getBoolean("duper.debug");
	
    String LS = System.getProperty("line.separator");
	
    public GUI(Display display) {
	this.display = display;
	pyjamasColor = new Color(display, 200, 200, 200);
	white = new Color(display, 255, 255, 255);
    }
	
    class SavePrefsSelectionListener {
	public void widgetSelected(SelectionEvent e) {
	    savePrefs();
	}
    }
	
    // must be called from GUI thread!
    void loadPrefs() {
	minSizeText.setText("" + prefs.getInt("minimum-size", 0));
	enableCacheBtn.setSelection(prefs.getBoolean("enable-cache", true));
	enableMmapBtn.setSelection(prefs.getBoolean("enable-mmap", true));
	enableAggressiveGcBtn.setSelection(prefs.getBoolean("aggressive-gc", true));
	enableOptimizedMd5Btn.setSelection(prefs.getBoolean("fast-md5", true));
	Preferences prefsExclude = prefs.node("exclude-patterns");
	String[] keys;
	try {
	    keys = prefsExclude.keys();
	} catch (BackingStoreException ex1) {
	    throw new RuntimeException(ex1);
	}
	for (int i=0; i < keys.length; i++) {
	    String pattern = (String) prefsExclude.get(keys[i], "");
	    if (!"".equals(pattern)) {
		excludeFiles.add(pattern);
	    }
	}
    }
	
    // must be called from GUI thread!
    void savePrefs() {
	try {
	    try {
		prefs.putInt("minimum-size", Integer.parseInt(minSizeText.getText()));
	    } catch (NumberFormatException ex1) { ex1.printStackTrace(); }
	    prefs.putBoolean("enable-cache", enableCacheBtn.getSelection());
	    prefs.putBoolean("enable-mmap", enableMmapBtn.getSelection());
	    prefs.putBoolean("aggressive-gc", enableAggressiveGcBtn.getSelection());
	    prefs.putBoolean("fast-md5", enableOptimizedMd5Btn.getSelection());
			
	    Preferences prefsExclude = prefs.node("exclude-patterns");
	    prefsExclude.clear();
			
	    int count=0;
	    for (Iterator i = excludeFiles.iterator();i.hasNext();) {
		prefsExclude.put("" + count, (String) i.next());
		count++;
	    }
			
	    prefs.flush();
	    if (debug) {
		System.out.println("prefs saved (" + prefs.absolutePath() + ")");
	    }
	} catch (BackingStoreException ex1) {
	    ex1.printStackTrace();
	    throw new RuntimeException(ex1);
	}
    }
	
    void defaultRootList() {
	String[] keys = new String[0];
	try {
	    Preferences roots = prefs.node("roots");
	    keys = roots.keys();
	    for (int i=0; i < keys.length; i++) {
		String value = roots.get(keys[i], "");
		if (!"".equals(value)) {
		    rootList.add(value);
		}
	    }
	} catch (BackingStoreException ex1) {
	    ex1.printStackTrace();
	    throw new RuntimeException(ex1);
	}
	if (keys.length == 0) {
	    File[] roots = File.listRoots();
	    for (int i=0; i < roots.length; i++) {
		if (isWindows && roots[i].getAbsolutePath().startsWith("A:")) continue;
		rootList.add(roots[i].getAbsolutePath());
	    }
	}
		
    }
	
    synchronized void saveRootList() {
	try {
	    Preferences roots = prefs.node("roots");
	    roots.clear();
			
	    String[] items = rootList.getItems();
	    for (int i=0; i < items.length; i++) {
		roots.put("" + i, items[i]);
	    }
	    savePrefs();
	} catch (BackingStoreException ex1) {
	    ex1.printStackTrace();
	    throw new RuntimeException(ex1);
	}
    }
	
    public void run() {
	shell = new Shell(display, SWT.CLOSE|SWT.MIN|SWT.MAX|SWT.BORDER|SWT.TITLE|SWT.RESIZE);
	shell.addShellListener(new ShellAdapter() {
		public void shellClosed(ShellEvent e) {
		    if (duper != null) {
			duper.abort();
			try {
			    shell.setText("Duper - waiting for scan to stop...");
			    scanThread.join();
			} catch (InterruptedException ex1) {}
		    }
		    savePrefs();
		}
							   
	    });
	GridLayout gl = new GridLayout();
	gl.numColumns = 2;
	shell.setLayout(gl);
	rootList = new List(shell, SWT.MULTI | SWT.V_SCROLL | SWT.BORDER);
	GridData gd = new GridData(GridData.FILL_HORIZONTAL);
	gd.horizontalSpan = 2;
	gd.heightHint = 100;
	rootList.setLayoutData(gd);
		
	defaultRootList();
		
	Composite comp1 = new Composite(shell, SWT.NONE);
	comp1.setLayout(new RowLayout());
	gd = new GridData();
	gd.horizontalSpan = 2;
	comp1.setLayoutData(gd);
		
	Button deleteRootBtn = new Button(comp1, SWT.NONE);
	deleteRootBtn.setText("Remove folder(s)");
	deleteRootBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    rootList.remove(rootList.getSelectionIndices());
		    saveRootList();
		}
	    });
		
	Button addRootBtn = new Button(comp1, SWT.NONE);
	addRootBtn.setText("Add folder");
	addRootBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    DirectoryDialog dialog = new DirectoryDialog(shell);
		    dialog.setMessage("Please choose a folder to add");
		    String selected = dialog.open();
		    if (selected != null) {
			rootList.add(selected);
			saveRootList();
		    }
		}
	    });
		
		
	Composite comp4 = new Composite(shell, SWT.NONE);
	comp4.setLayout(new RowLayout());
	gd = new GridData();
	gd.horizontalSpan = 2;
	comp4.setLayoutData(gd);
		
	Button excludePatternBtn = new Button(comp4, SWT.NONE);
	excludePatternBtn.setText("Exclude patterns...");
	excludePatternBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    ListShell ls = new ListShell(shell, SWT.NONE);
		    ls.setText("Exclude file patterns");
		    ls.setMessage("Enter regular expressions or wildcards " +
				  "matching the files you wish " +
				  "to exclude. Press return after entering " +
				  "each pattern in the text box.");
		    ls.setList(excludeFiles);
		    Collection c = ls.open();
		    if (c != null) {
			if (debug) {
			    System.out.println("Exclude patterns: " + 
					       c);
			}
			excludeFiles = c;
		    }
		}
	    });
		
	Composite comp5 = new Composite(shell, SWT.NONE);
	FormLayout fl = new FormLayout();
	comp5.setLayout(fl);
	gd = new GridData();
	gd.horizontalSpan = 2;
	comp5.setLayoutData(gd);
		
	Label minSizeLabel = new Label(comp5, SWT.NONE);
	minSizeLabel.setText("Minimum file size");
		
	minSizeText = new Text(comp5, SWT.BORDER);
	minSizeText.setText("5120");
	minSizeText.addVerifyListener(new VerifyListener() {
		public void verifyText(VerifyEvent e) {
		    e.doit = e.text.matches("^[0-9]*$");
		}
	    });
	FormData formData = new FormData();
	formData.left = new FormAttachment(minSizeLabel, 5);
	formData.width = 80;
	minSizeText.setLayoutData(formData);
		
	formData = new FormData();
	formData.top = new FormAttachment(0, 3);
	minSizeLabel.setLayoutData(formData);
		
		
	Composite comp3 = new Composite(shell, SWT.NONE);
	comp3.setLayout(new RowLayout());
	gd = new GridData();
	gd.horizontalSpan = 2;
	comp3.setLayoutData(gd);
		
	enableCacheBtn = new Button(comp3, SWT.CHECK);
	enableCacheBtn.setSelection(true);
	enableCacheBtn.setText("Enable MD5 cache");
	enableCacheBtn.setToolTipText("Stores all MD5 checksums in " +
				      Duper.diskCacheFile + " for faster " +
				      "consecutive searchs (can get quite big over time, " + 
				      "and modified files goes unnoticed)");
		
	enableMmapBtn = new Button(comp3, SWT.CHECK);
	enableMmapBtn.setSelection(true);
	enableMmapBtn.setText("Memory-mapped I/O");
	enableMmapBtn.setToolTipText("Uses memory-mapped I/O, which may be faster " +
				     "for large files.");
		
	enableAggressiveGcBtn = new Button(comp3, SWT.CHECK);
	enableAggressiveGcBtn.setSelection(true);
	enableAggressiveGcBtn.setText("Agressive GC");
	enableAggressiveGcBtn.setToolTipText("Forces the garbage collector " +
					     "to run more often, to trim " +
					     "memory usage");
		
	enableOptimizedMd5Btn = new Button(comp3, SWT.CHECK);
	enableOptimizedMd5Btn.setSelection(true);
	enableOptimizedMd5Btn.setText("Optimized MD5 implementation");
	enableOptimizedMd5Btn.setToolTipText("Uses a customized MD5 implementation rather " +
					     "than the default provided with Java");
		
	Composite comp2 = new Composite(shell, SWT.NONE);
	comp2.setLayout(new RowLayout());
		
	startScanBtn = new Button(comp2, SWT.NONE);
	startScanBtn.setText("Start scan!");
	startScanBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    startScanThread();
		}
	    });
		
	stopScanBtn = new Button(comp2, SWT.NONE);
	stopScanBtn.setEnabled(false);
	stopScanBtn.setText("Stop scan");
	stopScanBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    stopScanThread();
		    stopScanBtn.setEnabled(false);
		    stopScanBtn.setText("Stopping...");
		}
	    });
		
		
	consoleList = new List(shell, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
	gd = new GridData(GridData.FILL_HORIZONTAL);
	gd.horizontalSpan = 2;
	gd.heightHint = 100;
	gd.widthHint = 350;
	consoleList.setLayoutData(gd);
		
	dupeTable = new Table(shell, SWT.CHECK | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER |
			      SWT.FULL_SELECTION);
	dupeTable.setHeaderVisible(true);
	dupeTable.addSelectionListener(new SelectionAdapter() {
		public void widgetDefaultSelected(SelectionEvent e) {
		    int index = dupeTable.getSelectionIndex();
		    if (index > -1) {
			TableItem item = dupeTable.getItem(index);
			Program p = (Program) item.getData("program");
			if (p != null) {
			    p.execute(filenameFor(item));
			}
		    }
		}
	    });
		
	gd = new GridData(GridData.FILL_BOTH);
	gd.horizontalSpan = 2;
	gd.heightHint = 170;
	dupeTable.setLayoutData(gd);
		
	TableColumn namecol = new TableColumn(dupeTable, SWT.LEFT);
	namecol.setText("Filename");
	namecol.setWidth(120);
		
	TableColumn pathcol = new TableColumn(dupeTable, SWT.LEFT);
	pathcol.setText("Folder");
	pathcol.setWidth(200);
		
	TableColumn sizecol = new TableColumn(dupeTable, SWT.LEFT);
	sizecol.setText("Size");
	sizecol.setWidth(80);
		
	//TableColumn md5col = new TableColumn(dupeTable, SWT.LEFT);
	//md5col.setText("MD5");
	//md5col.setWidth(200);
		
	TableColumn timecol = new TableColumn(dupeTable, SWT.LEFT);
	timecol.setText("Last modified");
	timecol.setWidth(100);
		
	Composite composite = new Composite(shell, SWT.NONE);
	//gd = new GridData(GridData.FILL_HORIZONTAL);
	gd = new GridData();
	gd.horizontalSpan = 2;
	composite.setLayoutData(gd);
	composite.setLayout(new GridLayout());
		
	selectStrategyCombo = new Combo(composite, SWT.DROP_DOWN | SWT.READ_ONLY);
	selectStrategyCombo.add("Uncheck all files");
	selectStrategyCombo.add("Check all files");
	selectStrategyCombo.add("Check all but first");
	selectStrategyCombo.add("Check all but last");
	selectStrategyCombo.add("Check all but newest");
	selectStrategyCombo.add("Check all but oldest");
	selectStrategyCombo.setText("Check files...");
		
	selectStrategyCombo.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    doAutoChecking();
		}
	    });
		
	Button deleteCheckedFilesBtn = new Button(composite, SWT.NONE);
	deleteCheckedFilesBtn.setText("Delete checked files!");
	deleteCheckedFilesBtn.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    deleteCheckedFiles();
		}
												   
	    });
	gd = new GridData();
	gd.horizontalSpan = 2;
	deleteCheckedFilesBtn.setLayoutData(gd);
		
	shell.setText("Duper");
		
	loadPrefs();
		
	shell.pack();
		
	/*
	  Point loc = shell.getLocation();
	  loc.y = 10;
	  shell.setLocation(loc);
	*/
		
	shell.setVisible(true);
	shell.setActive();
		
	try {
	    while (!shell.isDisposed()) {
		if (!display.readAndDispatch()) {
		    display.sleep();
		}
	    }
	} catch (Exception ex1) {
	    ex1.printStackTrace();
	    if (!shell.isDisposed()) {
		shell.setVisible(false);
		MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR|SWT.OK);
		mb.setText("Unhandled error");
		mb.setMessage("Duper has encountered an error and cannot " +
			      "continue. If this occurs repeatedly, please " +
			      "report the problem to the author at " + 
			      "<duper@dll.nu>." + LS + 
			      "Error: " + ex1.toString() + LS);
		mb.open();
	    } else {
		ex1.printStackTrace();
	    }
	}
	display.dispose();
	if (duper != null) duper.dispose();
    }
	
    void doAutoChecking() {
	int selected = selectStrategyCombo.getSelectionIndex();
	switch (selected) {
	case 0:
	    checkAll(false);
	    break;
	case 1:
	    checkAll(true);
	    break;
	case 2:
	    checkAllButFirst();
	    break;
	case 3:
	    checkAllButLast();
	    break;
	case 4:
	    checkAllButNewestOrOldest(true);
	    break;
	case 5:
	    checkAllButNewestOrOldest(false);
	    break;
	default:
	    System.out.println("Unkown selectStrategyCombo index: " + selected);
	    break;
	}
		
    }
	
    void deleteCheckedFiles() {
	final LinkedList list = new LinkedList();
	final Map tableIndexMap = new HashMap();
	TableItem[] items = dupeTable.getItems();
	for (int i=0; i < items.length; i++) {
	    if (items[i].getChecked()) {
		File f = new File(filenameFor(items[i]));
		list.add(f);
		tableIndexMap.put(f, new Integer(i));
	    }
	}
	long totalSize = 0;
	for (Iterator i = list.iterator();i.hasNext();) {
	    totalSize += ((File) i.next()).length();
	}
	double megs = ((double)totalSize) / 1024d / 1024d;
		
	MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING | SWT.YES | SWT.NO);
	mb.setText("Delete selected files");
	mb.setMessage("You have selected " + list.size() + " files, using " +
		      "a total of " + (megs > 1 ? (((int) megs) + " megabytes") : (totalSize + " bytes"))
		      + ". Are you SURE you want to delete those files?");
	if (mb.open() == SWT.YES) {
	    Thread t = new Thread() {
		    public void run() {
			reallyDeleteFiles(list, tableIndexMap);
		    }
		};
	    t.setDaemon(true);
	    t.start();
	}
    }
	
    void reallyDeleteFiles(LinkedList files, Map tableIndices) {
	boolean aborted = false;
	log("Deleting " + files.size() + " files...");
	int deleted = 0;
	long bytes = 0;
	LinkedList indicesDeleted = new LinkedList();
	for (Iterator i = files.iterator();!aborted && i.hasNext();) {
	    File f = (File) i.next();
	    long size = f.length();
	    if (f.delete()) {
		deleted++;
		bytes += size;
		indicesDeleted.add(tableIndices.get(f));
		log("Deleted " + f.getAbsolutePath());
	    } else {
		log("ERROR: Failed to delete " + f.getAbsolutePath());
	    }
	}
	final int indices[] = new int[indicesDeleted.size()];
	int c=0;
	for (Iterator i = indicesDeleted.iterator();i.hasNext();c++) {
	    indices[c] = ((Integer) i.next()).intValue();
	}
	display.asyncExec(new Runnable() {
		public void run() {
		    dupeTable.remove(indices);
		}
	    });
		
	log("Done: deleted " + deleted + " files (" + bytes + " bytes freed).");
    }
	
    String md5For(TableItem item) {
	return (String) item.getData("md5");
    }
	
    String filenameFor(TableItem item) {
	return ((File) item.getData()).getAbsolutePath();
    }
	
    // I apology for the weird naming. I have a severe hangover.
    void checkAllButNewestOrOldest(boolean notnewest) {
	checkAll(true);
	TableItem[] items = dupeTable.getItems();
	Map fileAgeSets = new HashMap();
	Map fileIndices = new HashMap();
	// two passes: first, iterate through all files and create a 
	// SortedSet for each MD5 sum, and place each SortedSet into
	// a Map with the MD5 as key. Let the SortedSet use a comparator
	// that compares according to lastModified().
	// We can then iterate through the Map and uncheck the first
	// or last file in each set.
		
	for (int i=0; i < items.length; i++) {
	    File file = new File(filenameFor(items[i]));
	    String md5 = md5For(items[i]);
	    String name = file.getAbsolutePath();
			
	    SortedSet ages = (SortedSet) fileAgeSets.get(md5);
	    if (ages == null) {
		ages = new TreeSet(new Comparator() {
			public int compare(Object o1, Object o2) {
			    long diff = ((File) o1).lastModified() - ((File) o2).lastModified();
			    return diff > 0 ? 1 : diff == 0 ? 0 : -1;
			}
		    });
		fileAgeSets.put(md5, ages);
	    }
	    ages.add(file);
	    fileIndices.put(name, new Integer(i));
	}
	for (Iterator i = fileAgeSets.entrySet().iterator();i.hasNext();) {
	    Map.Entry entry = (Map.Entry) i.next();
	    File file;
	    if (notnewest) {
		file = (File) ((SortedSet) entry.getValue()).last();
	    } else {
		file = (File) ((SortedSet) entry.getValue()).first();
	    }
	    items[((Integer) fileIndices.get(file.getAbsolutePath())).intValue()].setChecked(false);
	}
    }
	
    void checkAllButLast() {
	checkAll(true);
	TableItem[] items = dupeTable.getItems();
	String lastMD5 = null;
	TableItem lastItem = null;
	for (int i=0; i < items.length; i++) {
	    String md5 = md5For(items[i]);
	    if (!md5.equals(lastMD5)) {
		if (lastItem != null) {
		    lastItem.setChecked(false);
		}
	    }
	    lastMD5 = md5;
	    lastItem = items[i];
	}
	if (md5For(items[items.length-2]).equals(md5For(items[items.length-1]))) {
	    items[items.length-1].setChecked(false);
	}
    }
	
    void checkAllButFirst() {
	TableItem[] items = dupeTable.getItems();
	String lastMD5 = null;
	for (int i=0; i < items.length; i++) {
	    String md5 = md5For(items[i]);
	    if (md5.equals(lastMD5)) {
		items[i].setChecked(true);
	    } else {
		items[i].setChecked(false);
	    }
	    lastMD5 = md5;
	}
    }
	
    void checkAll(boolean checked) {
	TableItem[] items = dupeTable.getItems();
	for (int i=0; i < items.length; i++) {
	    items[i].setChecked(checked);
	}
    }
	
	
    void startScanThread() {
	resetEnableStates(true);
		
	final LinkedList roots = new LinkedList();
	String[] items = rootList.getItems();
	for (int i=0; i < items.length; i++) {
	    roots.add(new File(items[i]));
	}
	// copy GUI variables to finals to pass them onto the scan thread
	final boolean enableCache = enableCacheBtn.getSelection();
	final boolean aggressiveGC = enableAggressiveGcBtn.getSelection();
	final boolean mmapIO = enableMmapBtn.getSelection();
	final boolean defaultMd5 = !enableOptimizedMd5Btn.getSelection();
	final int minimumSize;
	try {
	    minimumSize = Integer.parseInt(minSizeText.getText());
	} catch (NumberFormatException ex1) {
	    throw new RuntimeException("oops!");
	}
	scanThread = new Thread() {
		public void run() {
		    try {
			if (duper != null) duper.dispose();
			duper = new Duper(roots, enableCache, defaultMd5);
			for (Iterator i = excludeFiles.iterator();i.hasNext();) {
			    duper.addFileIgnorePattern((String) i.next());
			}
			duper.setProgressListener(GUI.this);
			duper.useMmapIO = mmapIO;
			duper.aggressiveGC = aggressiveGC;
			duper.minimumSize = minimumSize;
			duper.processRoots();
		    } catch (Exception ex1) {
			ex1.printStackTrace();
			exception = ex1;
			scanStop(true);
		    }
		}
	    };
	scanThread.setDaemon(true);
	scanThread.setName("Traversal-Thread");
	scanThread.start();
    }
	
    void log(final String s) {
	display.asyncExec(new Runnable() {
		public void run() {
		    if (display.isDisposed() || consoleList.isDisposed()) {
			System.out.println("[log] " + s);
			return;
		    }
		    consoleList.add(s);
		    consoleList.setSelection(consoleList.getItemCount()-1);
		    consoleList.showSelection();
		}
	    });
    }
	
    DateFormat dateFormat = DateFormat.getDateTimeInstance();
    void showScanResults() {
	log("--------------------------------------------------------------------------");
	log("Files: " + duper.filesProcessed + ", directories: " + 
	    duper.directoriesProcessed + ", bytes: " + duper.totalBytes);
	log(duper.dupeCount + " duplicates found.");
	log("MD5 calculated for " + duper.totalFilesChecksummed + 
	    " files (" + duper.totalBytesChecksummed + " bytes)");
	long millis = duper.getDuration();
	long seconds = millis / 1000l;
	log("Duration: " + seconds + " seconds");
	log("--------------------------------------------------------------------------");
		
	display.syncExec(new Runnable() {
		public void run() {
		    dupeTable.removeAll();
		}
	    });
	Collection dupelist = duper.getDupes();
	String lastmd5 = null;
	boolean pyjamas = false;
	for (Iterator i = dupelist.iterator();i.hasNext();) {
	    Collection files = (Collection) i.next();
	    for (Iterator j = files.iterator();j.hasNext();) {
		final File f = (File) j.next();
		final String md5;
		try {
		    md5 = duper.getMD5(f);
		} catch (IOException ex1) {
		    throw new RuntimeException("I/O error fetching MD5: " + ex1.toString());
		}
				
		if (!md5.equals(lastmd5)) {
		    pyjamas = !pyjamas;
		}
		final boolean _pyjamas = pyjamas;
		lastmd5 = md5;
				
		display.syncExec(new Runnable() {
			public void run() {
			    TableItem item = new TableItem(dupeTable, SWT.NONE);
								 
			    String name = f.getName();
			    int lastDot = name.lastIndexOf(".");
			    // if the file has an extension, check to see if it
			    // has an icon associated with it.
			    if (lastDot > 0 && lastDot != name.length()-1) {
				String extension = name.substring(lastDot+1);
				Program program = Program.findProgram(extension);
				if (program != null) {
				    ImageData id = program.getImageData();
				    if (id != null) {
					final Image image = new Image(display, id);
					item.addDisposeListener(new DisposeListener() {
						public void widgetDisposed(DisposeEvent e) {
						    image.dispose();
						}
					    });
					item.setImage(0, image);
				    }
				    item.setData("program", program);
				}
			    }
			    item.setText(0, f.getName());
			    item.setText(1, f.getParentFile().getAbsolutePath());
			    item.setText(2, "" + f.length());
			    item.setText(3, dateFormat.format(new Date(f.lastModified())));
			    item.setData(f);
			    item.setData("md5", md5);
								 
			    item.setChecked(false);
			    if (_pyjamas) {
				item.setBackground(pyjamasColor);
			    } else {
				item.setBackground(white);
			    }
			}
		    });
	    }
	}
	display.syncExec(new Runnable() {
		public void run() {
		    doAutoChecking();
		}
	    });
		
    }
	
    public void ioError(File f, IOException e) {
	e.printStackTrace();
	log("Error reading " + f + ": " + e.getMessage());
    }
	
    public void enteringDirectory(File f) {
	log("Scanning: " + f.getAbsolutePath());
    }
	
    public void scanStart() {
	log("Scan started.");
    }
	
    public void cacheLoadStart() {
	log("Loading cached checksums and initializing cache...");
    }
	
    public void cacheLoadStop() {
	log("Cache loaded.");
    }
	
    public void scanStop(boolean aborted) {
	if (display.isDisposed()) return;
		
	if (aborted) {
	    if (exception != null) {
		log("Scan error: " + exception.toString());
		log("Scan was aborted due to an error.");
	    } else {
		log("Scan aborted by user.");
	    }
	} else {
	    log("Scan complete.");
	    showScanResults();
	}
	display.asyncExec(new Runnable() {
		public void run() {
		    if (shell.isDisposed()) return;
		    stopScanBtn.setText("Stop scan");
		    resetEnableStates(false);
		}
	    });
    }
	
    void resetEnableStates(boolean isScanning) {
	startScanBtn.setEnabled(!isScanning);
	stopScanBtn.setEnabled(isScanning);
	enableCacheBtn.setEnabled(!isScanning);
	enableAggressiveGcBtn.setEnabled(!isScanning);
	enableMmapBtn.setEnabled(!isScanning);
	enableOptimizedMd5Btn.setEnabled(!isScanning);		
    }
	
    void stopScanThread() {
	log("Waiting for scan to stop...");
	duper.abort();
    }
    
    public static void main(String[] argv) {
	Display.setAppName("Duper");
	new GUI(new Display()).run();
    }
}
