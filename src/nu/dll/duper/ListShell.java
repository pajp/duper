package nu.dll.duper;

import org.eclipse.swt.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.browser.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.program.Program;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

import java.util.regex.*;

public class ListShell {
    Display display;
    Shell shell;
	
    List list;
	
    Button okButton;
    Button cancelButton;
	
    Object mutex = new Object();
	
    boolean buttonClicked = false;
    boolean cancelled = false;
	
    Label messageLabel;
	
    public ListShell(Shell parent, int style) {
	shell = new Shell(parent, SWT.DIALOG_TRIM);
	shell.addShellListener(new ShellAdapter() {
		public void shellClosed(ShellEvent e) {
		    if (list.getItemCount() != 0) {
			e.doit = confirmCancel();
		    }
		}
	    });
	display = shell.getDisplay();
	GridLayout l = new GridLayout();
	l.numColumns = 2;
	shell.setLayout(l);
		
	GridData gd = new GridData(GridData.FILL_HORIZONTAL);
	gd.heightHint = 100;
	gd.widthHint = 40;
	gd.horizontalSpan = 2;
		
	list = new List(shell, SWT.BORDER);
	list.setLayoutData(gd);
	list.addKeyListener(new KeyAdapter() {
		public void keyPressed(KeyEvent e) {
		    int index = list.getSelectionIndex();
		    if (e.keyCode == SWT.DEL && index > -1) {
			list.remove(index);		
		    }
		}
	    });
	gd = new GridData();
	gd.horizontalSpan = 2;
	gd.widthHint = 200;
	messageLabel = new Label(shell, SWT.WRAP);
	messageLabel.setLayoutData(gd);
		
	gd = new GridData(GridData.FILL_HORIZONTAL);
	gd.horizontalSpan = 2;
	final Text addText = new Text(shell, SWT.BORDER);
	addText.setLayoutData(gd);
	addText.setFocus();
		
	addText.addSelectionListener(new SelectionAdapter() {
		public void widgetDefaultSelected(SelectionEvent e) {
		    String pattern = addText.getText();
		    if (pattern.trim().equals("")) {
			return;
		    }
		    System.out.println("Pattern: " + pattern);
		    if (pattern.matches("^\\*\\..{1,}")) {
			MessageBox mb = new MessageBox(shell, 
						       SWT.ICON_QUESTION |
						       SWT.YES |
						       SWT.NO);
			mb.setText("Regular expression conversion");
			mb.setMessage("Do you want to convert this wildcard " +
				      "pattern to a regular expression " +
				      "(otherwise you are likely to get a " +
				      "pattern compile error)?");
			if (mb.open() == SWT.YES) {
			    System.out.println("old pattern: " + pattern);
			    pattern = pattern.replaceAll("^\\*\\.(.{1,})", "^.*\\\\.$1\\$");
			    System.out.println("new pattern: " + pattern);
			}
		    }
		    try {
			java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
		    } catch (PatternSyntaxException ex1) {
			MessageBox mb2 = new MessageBox(shell, 
							SWT.ICON_ERROR |
							SWT.OK);
			mb2.setText("Regular expression error");
			mb2.setMessage("The pattern you entered does not " +
				       "constitute a valid regular expression.\r\n" +
				       ex1.getMessage());
			mb2.open();
			pattern = null;
		    }
									 
		    if (pattern != null) {
			list.add(pattern);
			addText.setText("");
		    }
		}
	    });
		
	okButton = new Button(shell, SWT.NONE);
	okButton.setText("OK");
	okButton.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    buttonClicked = true;
		    if (list.getItemCount() == 0) {
			MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING |
						       SWT.YES | SWT.NO);
			mb.setText("No patterns defined");
			mb.setMessage("No patterns has been entered! " +
				      "Do you wish to continue?");
			if (mb.open() == SWT.NO) {
			    buttonClicked = false;
			}
		    }
		    if (!addText.getText().trim().equals("")) {
			MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION |
						       SWT.YES | SWT.NO);
			mb.setText("Bling");
			mb.setMessage("You have entered something in the expression field " +
				      "but didn't press return to add it to the list. Do you " +
				      "wish to continue (and thus discard that information)?");
			if (mb.open() == SWT.NO) {
			    buttonClicked = false;
			}
		    }
		}
	    });
		
	gd = new GridData();
	cancelButton = new Button(shell, SWT.NONE);
	cancelButton.setText("Cancel");
	cancelButton.addSelectionListener(new SelectionAdapter() {
		public void widgetSelected(SelectionEvent e) {
		    if (confirmCancel()) {
			buttonClicked = true;
			cancelled = true;
		    }
		}
	    });
		
    }
	
    public void setText(String s) {
	shell.setText(s);
    }
	
    public void setMessage(String s) {
	messageLabel.setText(s);
    }
	
    public void setList(final Collection c) {
	display.syncExec(new Runnable() {
		public void run() {
		    for (Iterator i = c.iterator(); i.hasNext();) {
			list.add((String) i.next());
		    }
		}
	    });
    }
    
    boolean confirmCancel() {
	MessageBox mb = new MessageBox(shell, SWT.ICON_WARNING|
				       SWT.YES | SWT.NO);
	mb.setText("Discard changes?");
	mb.setMessage("Cancelling this dialog will discard " +
		      "any changed you have made. Are you " +
		      "sure?");
	return mb.open() == SWT.YES;
    }
	
    public Collection open() {
	shell.pack();
	shell.open();
	while (!buttonClicked &&!shell.isDisposed()) {
	    if (!buttonClicked && !display.readAndDispatch()) {
		display.sleep();
	    }
	}
		
	Collection itemColl = new LinkedList();
	if (shell.isDisposed()) {
	    cancelled = true;
	} else {
	    String[] items = list.getItems();
	    for (int i=0; i < items.length; i++) {
		itemColl.add(items[i]);
	    }
			
	    shell.dispose();
	}
	if (cancelled) return null;
		
	return itemColl;
    }
	
	
}
