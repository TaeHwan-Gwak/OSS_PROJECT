/*
The MIT License

Copyright (c) 2015-2023 Valentyn Kolesnikov (https://github.com/javadev/file-manager)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package com.github.filemanager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.*;
import javax.swing.tree.*;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * A basic File Manager. Requires 1.6+ for the Desktop &amp; SwingWorker classes, amongst other
 * minor things.
 *
 * <p>Includes support classes FileTableModel &amp; FileTreeCellRenderer.
 *
 * <p>TODO Bugs
 *
 * <ul>
 *   <li>Still throws occasional AIOOBEs and NPEs, so some update on the EDT must have been missed.
 *   <li>Fix keyboard focus issues - especially when functions like rename/delete etc. are called
 *       that update nodes &amp; file lists.
 *   <li>Needs more testing in general.
 *       <p>TODO Functionality
 *   <li>Implement Read/Write/Execute checkboxes
 *   <li>Implement Copy
 *   <li>Extra prompt for directory delete (camickr suggestion)
 *   <li>Add File/Directory fields to FileTableModel
 *   <li>Double clicking a directory in the table, should update the tree
 *   <li>Move progress bar?
 *   <li>Add other file display modes (besides table) in CardLayout?
 *   <li>Menus + other cruft?
 *   <li>Implement history/back
 *   <li>Allow multiple selection
 *   <li>Add file search
 * </ul>
 */
public class FileManager {

    /**
     * Title of the application
     */
    public static final String APP_TITLE = "FileManager";
    /**
     * Used to open/edit/print files.
     */
    private Desktop desktop;
    /**
     * Provides nice icons and names for files.
     */
    private FileSystemView fileSystemView;

    /**
     * currently selected File.
     */
    private File currentFile;

    /**
     * Main GUI container
     */
    private JPanel gui;

    /**
     * File-system tree. Built Lazily
     */
    private JTree tree;

    private DefaultTreeModel treeModel;

    /**
     * Directory listing
     */
    private JTable table;

    private JProgressBar progressBar;
    /**
     * Table model for File[].
     */
    private FileTableModel fileTableModel;

    private ListSelectionListener listSelectionListener;
    private boolean cellSizesSet = false;
    private int rowIconPadding = 6;

    /* File controls. */
    private JButton openFile;
    private JButton editFile;
    private JButton deleteFile;
    private JButton newFile;
    private JButton addButton;
    private JButton restoreButton;
    private JButton rmButton;
    private JButton mvButton;

    private JButton branchCreateButton;

    private JLabel fileName;
    private JTextField path;
    private JLabel date;
    private JLabel size;
    private JCheckBox readable;
    private JCheckBox writable;
    private JCheckBox executable;
    private JRadioButton isDirectory;
    private JRadioButton isFile;
    private JButton initButton;

    private JButton commitButton;
    private JPanel commitPanel;

    /* GUI options/containers for new File/Directory creation.  Created lazily. */
    private JPanel newFilePanel;
    private JRadioButton newTypeFile;
    private JTextField name;

    public Container getGui() {
        if (gui == null) {
            gui = new JPanel(new BorderLayout(3, 3));
            gui.setBorder(new EmptyBorder(5, 5, 5, 5));

            fileSystemView = FileSystemView.getFileSystemView();
            desktop = Desktop.getDesktop();

            JPanel detailView = new JPanel(new BorderLayout(3, 3));
            // fileTableModel = new FileTableModel();

            table = new JTable();
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoCreateRowSorter(true);
            table.setShowVerticalLines(false);

            listSelectionListener =
                    new ListSelectionListener() {
                        @Override
                        public void valueChanged(ListSelectionEvent lse) {
                            int row = table.getSelectionModel().getLeadSelectionIndex();
                            setFileDetails(((FileTableModel) table.getModel()).getFile(row));
                        }
                    };
            table.getSelectionModel().addListSelectionListener(listSelectionListener);
            JScrollPane tableScroll = new JScrollPane(table);
            Dimension d = tableScroll.getPreferredSize();
            tableScroll.setPreferredSize(
                    new Dimension((int) d.getWidth(), (int) d.getHeight() / 2));
            detailView.add(tableScroll, BorderLayout.CENTER);

            // the File tree
            DefaultMutableTreeNode root = new DefaultMutableTreeNode();
            treeModel = new DefaultTreeModel(root);

            TreeSelectionListener treeSelectionListener =
                    new TreeSelectionListener() {
                        public void valueChanged(TreeSelectionEvent tse) {
                            DefaultMutableTreeNode node =
                                    (DefaultMutableTreeNode) tse.getPath().getLastPathComponent();
                            showChildren(node);
                            setFileDetails((File) node.getUserObject());
                        }
                    };

            // show the file system roots.
            File[] roots = fileSystemView.getRoots();
            for (File fileSystemRoot : roots) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(fileSystemRoot);
                root.add(node);
                // showChildren(node);
                //
                File[] files = fileSystemView.getFiles(fileSystemRoot, true);
                for (File file : files) {
                    if (file.isDirectory()) {
                        node.add(new DefaultMutableTreeNode(file));
                    }
                }
                //
            }

            tree = new JTree(treeModel);
            tree.setRootVisible(false);
            tree.addTreeSelectionListener(treeSelectionListener);
            tree.setCellRenderer(new FileTreeCellRenderer());
            tree.expandRow(0);
            JScrollPane treeScroll = new JScrollPane(tree);

            // as per trashgod tip
            tree.setVisibleRowCount(15);

            Dimension preferredSize = treeScroll.getPreferredSize();
            Dimension widePreferred = new Dimension(200, (int) preferredSize.getHeight());
            treeScroll.setPreferredSize(widePreferred);

            // details for a File
            JPanel fileMainDetails = new JPanel(new BorderLayout(4, 2));
            fileMainDetails.setBorder(new EmptyBorder(0, 6, 0, 6));

            JPanel fileDetailsLabels = new JPanel(new GridLayout(0, 1, 2, 2));
            fileMainDetails.add(fileDetailsLabels, BorderLayout.WEST);

            JPanel fileDetailsValues = new JPanel(new GridLayout(0, 1, 2, 2));
            fileMainDetails.add(fileDetailsValues, BorderLayout.CENTER);

            fileDetailsLabels.add(new JLabel("File", JLabel.TRAILING));
            fileName = new JLabel();
            fileDetailsValues.add(fileName);
            fileDetailsLabels.add(new JLabel("Path/name", JLabel.TRAILING));
            path = new JTextField(5);
            path.setEditable(false);
            fileDetailsValues.add(path);
            fileDetailsLabels.add(new JLabel("Last Modified", JLabel.TRAILING));
            date = new JLabel();
            fileDetailsValues.add(date);
            fileDetailsLabels.add(new JLabel("File size", JLabel.TRAILING));
            size = new JLabel();
            fileDetailsValues.add(size);
            fileDetailsLabels.add(new JLabel("Type", JLabel.TRAILING));

            JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            isDirectory = new JRadioButton("Directory");
            isDirectory.setEnabled(false);
            flags.add(isDirectory);

            isFile = new JRadioButton("File");
            isFile.setEnabled(false);
            flags.add(isFile);
            fileDetailsValues.add(flags);

            int count = fileDetailsLabels.getComponentCount();
            for (int ii = 0; ii < count; ii++) {
                fileDetailsLabels.getComponent(ii).setEnabled(false);
            }

            JToolBar toolBar = new JToolBar();
            // mnemonics stop working in a floated toolbar
            toolBar.setFloatable(false);

            openFile = new JButton("Open");
            openFile.setMnemonic('o');

            openFile.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            try {
                                desktop.open(currentFile);
                            } catch (Throwable t) {
                                showThrowable(t);
                            }
                            gui.repaint();
                        }
                    });
            toolBar.add(openFile);

            editFile = new JButton("Edit");
            editFile.setMnemonic('e');
            editFile.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            try {
                                desktop.edit(currentFile);
                            } catch (Throwable t) {
                                showThrowable(t);
                            }
                        }
                    });
            toolBar.add(editFile);


            // Check the actions are supported on this platform!
            openFile.setEnabled(desktop.isSupported(Desktop.Action.OPEN));
            editFile.setEnabled(desktop.isSupported(Desktop.Action.EDIT));

            toolBar.addSeparator();

            newFile = new JButton("New");
            newFile.setMnemonic('n');
            newFile.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            newFile();
                        }
                    });
            toolBar.add(newFile);

            JButton renameFile = new JButton("Rename");
            renameFile.setMnemonic('r');
            renameFile.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            renameFile();
                        }
                    });
            toolBar.add(renameFile);

            deleteFile = new JButton("Delete");
            deleteFile.setMnemonic('d');
            deleteFile.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            deleteFile();
                        }
                    });
            toolBar.add(deleteFile);

            toolBar.addSeparator();

            // git Buttons
            toolBar.add(new JLabel("git "));

            initButton = new JButton("init");
            //initButton.setMnemonic('');
            initButton.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            initButton();
                        }
                    });
            toolBar.add(initButton);

            // cloneButton soon

            toolBar.addSeparator();

            addButton = new JButton("add");
            addButton.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            addButton();
                        }
                    });
            toolBar.add(addButton);

            restoreButton = new JButton("restore");
            restoreButton.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            restoreButton();
                        }
                    });
            toolBar.add(restoreButton);

            rmButton = new JButton("rm");
            rmButton.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            rmButton();
                        }
                    });
            toolBar.add(rmButton);

            mvButton = new JButton("mv");
            mvButton.addActionListener(
                    new ActionListener() {
                        public void actionPerformed(ActionEvent ae) {
                            mvButton();
                        }
                    });
            toolBar.add(mvButton);

            toolBar.addSeparator();

            commitButton = new JButton("commit");
            commitButton.addActionListener(
                    new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            commitButton();
                        }
                    }
            );
            toolBar.add(commitButton);

            // git branch Buttons
            toolBar.addSeparator();

            toolBar.add(new JLabel("branch "));

            branchCreateButton = new JButton("create");
            branchCreateButton.addActionListener(
                    new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) { branchCreateButton(); }
                    }
            );
            toolBar.add(branchCreateButton);

            JPanel fileView = new JPanel(new BorderLayout(3, 3));

            fileView.add(toolBar, BorderLayout.NORTH);
            fileView.add(fileMainDetails, BorderLayout.CENTER);

            detailView.add(fileView, BorderLayout.SOUTH);

            JSplitPane splitPane =
                    new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailView);
            gui.add(splitPane, BorderLayout.CENTER);

            JPanel simpleOutput = new JPanel(new BorderLayout(3, 3));
            progressBar = new JProgressBar();
            simpleOutput.add(progressBar, BorderLayout.EAST);
            progressBar.setVisible(false);

            gui.add(simpleOutput, BorderLayout.SOUTH);
        }
        return gui;
    }

    public void showRootFile() {
        // ensure the main files are displayed
        tree.setSelectionInterval(0, 0);
    }

    private TreePath findTreePath(File find) {
        for (int ii = 0; ii < tree.getRowCount(); ii++) {
            TreePath treePath = tree.getPathForRow(ii);
            Object object = treePath.getLastPathComponent();
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) object;
            File nodeFile = (File) node.getUserObject();

            if (nodeFile.equals(find)) {
                return treePath;
            }
        }
        // not found!
        return null;
    }

    private void renameFile() {
        if (currentFile == null) {
            showErrorMessage("No file selected to rename.", "Select File");
            return;
        }

        String renameTo = JOptionPane.showInputDialog(gui, "New Name");
        if (renameTo != null) {
            try {
                boolean directory = currentFile.isDirectory();
                TreePath parentPath = findTreePath(currentFile.getParentFile());
                DefaultMutableTreeNode parentNode =
                        (DefaultMutableTreeNode) parentPath.getLastPathComponent();

                boolean renamed =
                        currentFile.renameTo(new File(currentFile.getParentFile(), renameTo));
                if (renamed) {
                    if (directory) {
                        // rename the node..

                        // delete the current node..
                        TreePath currentPath = findTreePath(currentFile);
                        System.out.println(currentPath);
                        DefaultMutableTreeNode currentNode =
                                (DefaultMutableTreeNode) currentPath.getLastPathComponent();

                        treeModel.removeNodeFromParent(currentNode);

                        // add a new node..
                    }

                    showChildren(parentNode);
                } else {
                    String msg = "The file '" + currentFile + "' could not be renamed.";
                    showErrorMessage(msg, "Rename Failed");
                }
            } catch (Throwable t) {
                showThrowable(t);
            }
        }
        gui.repaint();
    }

    private void deleteFile() {
        if (currentFile == null) {
            showErrorMessage("No file selected for deletion.", "Select File");
            return;
        }

        int result =
                JOptionPane.showConfirmDialog(
                        gui,
                        "Are you sure you want to delete this file?",
                        "Delete File",
                        JOptionPane.ERROR_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                System.out.println("currentFile: " + currentFile);
                TreePath parentPath = findTreePath(currentFile.getParentFile());
                System.out.println("parentPath: " + parentPath);
                DefaultMutableTreeNode parentNode =
                        (DefaultMutableTreeNode) parentPath.getLastPathComponent();
                System.out.println("parentNode: " + parentNode);

                boolean directory = currentFile.isDirectory();
                if (FileUtils.deleteQuietly(currentFile)) {
                    if (directory) {
                        // delete the node..
                        TreePath currentPath = findTreePath(currentFile);
                        System.out.println(currentPath);
                        DefaultMutableTreeNode currentNode =
                                (DefaultMutableTreeNode) currentPath.getLastPathComponent();

                        treeModel.removeNodeFromParent(currentNode);
                    }

                    showChildren(parentNode);
                } else {
                    String msg = "The file '" + currentFile + "' could not be deleted.";
                    showErrorMessage(msg, "Delete Failed");
                }
            } catch (Throwable t) {
                showThrowable(t);
            }
        }
        gui.repaint();
    }

    private void newFile() {
        if (currentFile == null) {
            showErrorMessage("No location selected for new file.", "Select Location");
            return;
        }

        if (newFilePanel == null) {
            newFilePanel = new JPanel(new BorderLayout(3, 3));

            JPanel southRadio = new JPanel(new GridLayout(1, 0, 2, 2));
            newTypeFile = new JRadioButton("File", true);
            JRadioButton newTypeDirectory = new JRadioButton("Directory");
            ButtonGroup bg = new ButtonGroup();
            bg.add(newTypeFile);
            bg.add(newTypeDirectory);
            southRadio.add(newTypeFile);
            southRadio.add(newTypeDirectory);

            name = new JTextField(15);

            newFilePanel.add(new JLabel("Name"), BorderLayout.WEST);
            newFilePanel.add(name);
            newFilePanel.add(southRadio, BorderLayout.SOUTH);
        }

        int result =
                JOptionPane.showConfirmDialog(
                        gui, newFilePanel, "Create File", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                boolean created;
                File parentFile = currentFile;
                if (!parentFile.isDirectory()) {
                    parentFile = parentFile.getParentFile();
                }
                File file = new File(parentFile, name.getText());
                if (newTypeFile.isSelected()) {
                    created = file.createNewFile();
                } else {
                    created = file.mkdir();
                }
                if (created) {

                    TreePath parentPath = findTreePath(parentFile);
                    DefaultMutableTreeNode parentNode =
                            (DefaultMutableTreeNode) parentPath.getLastPathComponent();

                    if (file.isDirectory()) {
                        // add the new node..
                        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(file);

                        TreePath currentPath = findTreePath(currentFile);
                        DefaultMutableTreeNode currentNode =
                                (DefaultMutableTreeNode) currentPath.getLastPathComponent();

                        treeModel.insertNodeInto(newNode, parentNode, parentNode.getChildCount());
                    }

                    showChildren(parentNode);
                } else {
                    String msg = "The file '" + file + "' could not be created.";
                    showErrorMessage(msg, "Create Failed");
                }
            } catch (Throwable t) {
                showThrowable(t);
            }
        }
        gui.repaint();
    }



    private void addButton() {
        if (currentFile == null) {
            showErrorMessage("No file selected for git add.", "Select File");
            return;
        }

        // if the directory doesn't use git, can't use git command.
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
            return; // Exit the method without creating the add Panel.
        }

        int result =
                JOptionPane.showConfirmDialog(
                        gui,
                        "Are you sure you want to git add this file?",
                        "Git Add File",
                        JOptionPane.ERROR_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                String file = currentFile.getName();
                String path = currentFile.getPath().replace(file, "");

                String addCommand = "git add ";
                String cmd = "cd " + path + " && " + addCommand + file;
                Process p;
                String[] command = {"/bin/sh", "-c", cmd};
                p = Runtime.getRuntime().exec(command);
            } catch (Throwable t) {
                showThrowable(t);
            }
        }

        gui.repaint();
    }

    private void restoreButton() {
        if (currentFile == null) {
            showErrorMessage("No file selected for git restore.", "Select File");
            return;
        }

        // if the directory doesn't use git, can't use git command.
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
            return; // Exit the method without creating the restore Panel.
        }

        Object[] options = {"Cancel", "restore --staged", "restore"};

        int result = JOptionPane.showOptionDialog(
                gui,
                "Are you sure you want to git restore this file?",
                "Git Restore File",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (result == 2) {
            try {
                String file = currentFile.getName();
                String path = currentFile.getPath().replace(file, "");

                String checkCmd = "cd " + path + " && " + "git status -s " + file;
                Process check_p;
                String[] c_command = {"/bin/sh", "-c", checkCmd};
                check_p = Runtime.getRuntime().exec(c_command);

                BufferedReader reader = new BufferedReader(new InputStreamReader(check_p.getInputStream()));

                // 출력 결과를 저장할 문자열 버퍼 생성
                StringBuilder output = new StringBuilder();

                // 현재 파일의 status
                String status;

                // 한 줄씩 출력 결과를 읽어와 버퍼에 추가
                String line;
                if ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    status = output.toString().substring(0, 2);
                    if(status.equals("??")) {
                        showErrorMessage("Git doesn't trace that file. Press add first.","Untracked File");
                        return;
                    } else if(status.equals("A ")) {
                        showErrorMessage("If you want restore, click restore --staged","Added File ?");
                        return;
                    }

                    String gitReCommand = "git restore ";
                    String cmd = "cd " + path + " && " + gitReCommand + file;
                    Process p;
                    String[] command = {"/bin/sh", "-c", cmd};
                    p = Runtime.getRuntime().exec(command);
                }
            } catch (Throwable t) {
                showThrowable(t);
            }
        } else if (result == 1) {
            try {
                String file = currentFile.getName();
                String path = currentFile.getPath().replace(file, "");

                String checkCmd = "cd " + path + " && " + "git status -s " + file;
                Process check_p;
                String[] c_command = {"/bin/sh", "-c", checkCmd};
                check_p = Runtime.getRuntime().exec(c_command);

                BufferedReader reader = new BufferedReader(new InputStreamReader(check_p.getInputStream()));

                // 출력 결과를 저장할 문자열 버퍼 생성
                StringBuilder output = new StringBuilder();

                // 현재 파일의 status
                String status;

                // 한 줄씩 출력 결과를 읽어와 버퍼에 추가
                String line;
                if ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    status = output.toString().substring(0, 2);
                    if(status.equals("??")) {
                        showErrorMessage("Git doesn't trace that file. Press add first.","Untracked File");
                        return;
                    }

                    String gitReCommand = "git restore --staged ";
                    String cmd = "cd " + path + " && " + gitReCommand + file;
                    Process p;
                    String[] command = {"/bin/sh", "-c", cmd};
                    p = Runtime.getRuntime().exec(command);
                }
            } catch (Throwable t) {
                showThrowable(t);
            }
        }

        gui.repaint();
    }

    private void rmButton() {
        if (currentFile == null) {
            showErrorMessage("No file selected for git rm.", "Select File");
            return;
        }

        // if the directory doesn't use git, can't use git command.
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
            return; // Exit the method without creating the rm Panel.
        }

        try {

            String path = currentFile.getParent();
            String name = currentFile.getName();

            String cmd = "cd " + path + " && " + "git status -s " + name;
            Process p;

            String[] command = {"/bin/sh", "-c", cmd};
            p = Runtime.getRuntime().exec(command);

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            // 출력 결과를 저장할 문자열 버퍼 생성
            StringBuilder output = new StringBuilder();

            // 현재 파일의 status
            String status;

            // 한 줄씩 출력 결과를 읽어와 버퍼에 추가
            String line;
            if ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                status = output.toString().substring(0, 2);
                if(status.equals("??")) {
                    showErrorMessage("Git doesn't trace that file. Press add first.","Untracked File");
                    return;
                }
            }

        } catch (Throwable t) {
            showThrowable(t);
        }

        Object[] options = {"Cancel", "rm -cached", "rm"};

        int result = JOptionPane.showOptionDialog(
                gui,
                "Are you sure you want to git rm this file?",
                "Git Rm File",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (result == 2) {
            try {
                String file = currentFile.getName();
                String path = currentFile.getPath().replace(file, "");

                String gitRmCommand = "git rm ";
                String cmd = "cd " + path + " && " + gitRmCommand + file;
                Process p;
                String[] command = {"/bin/sh", "-c", cmd};
                p = Runtime.getRuntime().exec(command);
            } catch (Throwable t) {
                showThrowable(t);
            }
        } else if (result == 1) {
            try {
                String file = currentFile.getName();
                String path = currentFile.getPath().replace(file, "");

                String gitRmCommand = "git rm --cached ";
                String cmd = "cd " + path + " && " + gitRmCommand + file;
                Process p;
                String[] command = {"/bin/sh", "-c", cmd};
                p = Runtime.getRuntime().exec(command);
            } catch (Throwable t) {
                showThrowable(t);
            }
        }

        gui.repaint();
    }

    private void mvButton() {
        if (currentFile == null) {
            showErrorMessage("No file selected for git mv.", "Select File");
            return;
        }

        // if the directory doesn't use git, can't use git command.
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
            return; // Exit the method without creating the mv Panel.
        }

        try {
            String path = currentFile.getParent();
            String name = currentFile.getName();

            String cmd = "cd " + path + " && " + "git status -s " + name;
            Process p;

            String[] command = {"/bin/sh", "-c", cmd};
            p = Runtime.getRuntime().exec(command);

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            // 출력 결과를 저장할 문자열 버퍼 생성
            StringBuilder output = new StringBuilder();

            // 현재 파일의 status
            String status;

            // 한 줄씩 출력 결과를 읽어와 버퍼에 추가
            String line;
            if ((line = reader.readLine()) != null) {
                output.append(line).append("\n");

                status = output.toString().substring(0, 2);
                if(status.equals("??")) {
                    showErrorMessage("Git doesn't trace that file. Press add first.","Untracked File");
                    return;
                }
            }

        } catch (Throwable t) {
            showThrowable(t);
        }

        String moveTo =
                JOptionPane.showInputDialog(gui,
                        "Text new file name or new path you want to git mv this file.");

        //moveTo가 이동할 path이고, repository 밖의 경로일 경우 error
        if(moveTo.contains("/")){
            gitDir = findGitDir(currentFile.getAbsoluteFile());
            File newFile = new File(moveTo);
            File newGitDir = findGitDir(newFile.getAbsoluteFile());
            if (!gitDir.equals(newGitDir)) {
                showErrorMessage("This path is outside repository.","Outside Repository");
                return; // Exit the method without creating the mv Panel.
            }
        }

        if(moveTo != null) {
            try {
                
                String file = currentFile.getName();
                String path = currentFile.getParent();
                Process p;
                String cmd = "cd " + path + " && git mv " + file + " " + moveTo;
                String[] command = {"/bin/sh", "-c", cmd};
                p = Runtime.getRuntime().exec(command);

            } catch (Throwable t) {
                showThrowable(t);
            }
        }

        gui.repaint();
    }

    private void initButton() {
        if (currentFile == null) {
            showErrorMessage("No file selected for git init.", "Select File");
            return;
        }

        // file can't use git init command.
        if (!currentFile.isDirectory()){
            showErrorMessage("The file can't use git. choose the Directory.","File Can't Use Git");
            return;
        }

        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir != null) {
            showErrorMessage("This directory already use git.","Already Use Git Directory");
            return; // Exit the method without creating the init panel
        }

        int result =
                JOptionPane.showConfirmDialog(
                        gui,
                        "Are you sure you want to git init this file?",
                        "Git Init File",
                        JOptionPane.ERROR_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                //디렉토리 아닐 시 에러
                Process p;
                String cmd = "cd " + currentFile.getPath() + " && git init";
                String[] command = {"/bin/sh", "-c", cmd};
                p = Runtime.getRuntime().exec(command);
            } catch (Throwable t) {
                showThrowable(t);
            }
        }
        gui.repaint();
    }

    private void showErrorMessage(String errorMessage, String errorTitle) {
        JOptionPane.showMessageDialog(gui, errorMessage, errorTitle, JOptionPane.ERROR_MESSAGE);
    }

    private void showThrowable(Throwable t) {
        t.printStackTrace();
        JOptionPane.showMessageDialog(gui, t.toString(), t.getMessage(), JOptionPane.ERROR_MESSAGE);
        gui.repaint();
    }

    /**
     * Update the table on the EDT
     */
    private void setTableData(final File[] files) {
        SwingUtilities.invokeLater(
                new Runnable() {
                    public void run() {
                        if (fileTableModel == null) {
                            fileTableModel = new FileTableModel();
                            table.setModel(fileTableModel);
                        }
                        table.getSelectionModel()
                                .removeListSelectionListener(listSelectionListener);
                        fileTableModel.setFiles(files);
                        table.getSelectionModel().addListSelectionListener(listSelectionListener);
                        if (!cellSizesSet) {
                            Icon icon = fileSystemView.getSystemIcon(files[0]);

                            // size adjustment to better account for icons
                            table.setRowHeight(icon.getIconHeight() + rowIconPadding);

                            setColumnWidth(0, -1);
                            setColumnWidth(3, 60);
                            table.getColumnModel().getColumn(3).setMaxWidth(120);
                            setColumnWidth(4, -1);
                            setColumnWidth(5, -1);
                            setColumnWidth(6, -1);
                            setColumnWidth(7, -1);
                            setColumnWidth(8, -1);
                            setColumnWidth(9, -1);

                            cellSizesSet = true;
                        }
                    }
                });
    }

    private void setColumnWidth(int column, int width) {
        TableColumn tableColumn = table.getColumnModel().getColumn(column);
        if (width < 0) {
            // use the preferred width of the header..
            JLabel label = new JLabel((String) tableColumn.getHeaderValue());
            Dimension preferred = label.getPreferredSize();
            // altered 10->14 as per camickr comment.
            width = (int) preferred.getWidth() + 14;
        }
        tableColumn.setPreferredWidth(width);
        tableColumn.setMaxWidth(width);
        tableColumn.setMinWidth(width);
    }


    /**
     * findGitDir do finding the .git dir from currentFile variance.
     * if There is .git return .git's file.
     * else return null
     * */
    private File findGitDir(File directory) {
        File gitDir = new File(directory, ".git");
        if (gitDir.exists() && gitDir.isDirectory()) {
            return gitDir;
        } else {
            File parent = directory.getParentFile();
            return parent != null ? findGitDir(parent) : null;
        }
    }

    private void commitButton() {
        if (currentFile == null) {
            showErrorMessage("No location selected for commit.", "Select Location");
            return;
        }


        // Handle the case where there is no .git directory found
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git","No Git Directory");
            return; // Exit the method without creating the commit panel
        }

        // to separate ui and model to reopen the commit button.
        JPanel commitPanel = createCommitPanel();

        int result =
                JOptionPane.showConfirmDialog(
                        gui, commitPanel, "Commit Changes", JOptionPane.OK_CANCEL_OPTION);

        // if user clicked ok. do commit.
        if (result == JOptionPane.OK_OPTION) {
            try {
                JTextArea commitMessageArea = (JTextArea) commitPanel.getClientProperty("commitMessageArea");
                String commitMsg = commitMessageArea.getText();
                if (commitMsg.trim().isEmpty()) {
                    showErrorMessage("Commit message cannot be empty.", "Empty Commit Message");
                    return;
                }

                JTable stagedFilesTable = (JTable) commitPanel.getClientProperty("stagedFilesTable");
                if (stagedFilesTable.getRowCount() == 0) {
                    showErrorMessage("There's nothing to commit.", "Empty Commit Objects");
                    return;
                }

                // get the repository to git command.
                Repository repository =
                        new FileRepositoryBuilder().setWorkTree(currentFile.getAbsoluteFile()).setGitDir(gitDir).build();

                Git git = new Git(repository);
                git.commit().setMessage(commitMsg).call();
                git.close();

                JOptionPane.showMessageDialog(gui, "Successfully Committed", "Commit Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException | GitAPIException e) {
                showErrorMessage("An error occurred during the commit process.", "Commit Error");
            }
        }

        gui.repaint();
    }

    private JPanel createCommitPanel() {
        // main Panel on commit button.
        JPanel commitPanel = new JPanel(new BorderLayout(3, 3));

        try {
            // find .git from currentFile.
            File gitDir = findGitDir(currentFile.getAbsoluteFile());
            if (gitDir == null) {
                // Handle the case where there is no .git directory found
                showErrorMessage("This directory doesn't use git","No Git Directory");
            }

            // get the repository to use git command.
            Repository repository =
                    new FileRepositoryBuilder().setWorkTree(currentFile.getAbsoluteFile()).setGitDir(gitDir).build();
            Git git = new Git(repository);
            Status status = git.status().call();

            // get the staged files.
            Set<String> stagedFiles = new HashSet<>();
            stagedFiles.addAll(status.getAdded());
            stagedFiles.addAll(status.getChanged());
            stagedFiles.addAll(status.getRemoved());

            // set the staged files table.
            DefaultTableModel tableModel = new DefaultTableModel(new String[]{"Staged Files"}, 0);
            for (String filePath : stagedFiles) {
                tableModel.addRow(new Object[]{filePath});
            }
            JTable stagedFilesTable = new JTable(tableModel);
            stagedFilesTable.setModel(tableModel);

            // set the commit message panel.
            JPanel commitMessagePanel = new JPanel(new BorderLayout());
            JTextArea commitMessageArea = new JTextArea(5, 30);
            commitMessagePanel.add(new JLabel("Commit Message"), BorderLayout.NORTH);
            commitMessagePanel.add(commitMessageArea, BorderLayout.CENTER);

            // set the main panel for commitButton.
            commitPanel.add(new JLabel("Staged Files:"), BorderLayout.NORTH);
            commitPanel.add(new JScrollPane(stagedFilesTable), BorderLayout.CENTER);
            commitPanel.add(commitMessagePanel, BorderLayout.SOUTH);

            commitPanel.putClientProperty("commitMessageArea", commitMessageArea);
            commitPanel.putClientProperty("stagedFilesTable", stagedFilesTable);
        } catch (IOException | GitAPIException ee) {
            showErrorMessage("An error occurred while trying to load staged files.", "Staged Files Error");
        }

        return commitPanel;
    }
    private void branchCreateButton() {
        if (currentFile == null) {
            showErrorMessage("No location selected for branch creation.", "Select Location");
            return;
        }

        // if the directory doesn't use git, can't use git command.
        File gitDir = findGitDir(currentFile.getAbsoluteFile());
        if (gitDir == null) {
            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
            return; // Exit the method without creating branchCreatePanel.
        }

        // to separate ui and model to reopen the branch create button.
        JPanel branchCreatePanel = createBranchCreatePanel();

        int result =
                JOptionPane.showConfirmDialog(
                        gui, branchCreatePanel, "Create Branch", JOptionPane.OK_CANCEL_OPTION);

        // if user clicked ok. do branch creation.
        if (result == JOptionPane.OK_OPTION) {
            try {
                JTextField branchNameField = (JTextField) branchCreatePanel.getClientProperty("branchNameField");
                String branchName = branchNameField.getText();
                if (branchName.trim().isEmpty()) {
                    showErrorMessage("Branch name cannot be empty.", "Empty Branch Name");
                    return;
                }

                // get the repository to git command.
                Repository repository =
                        new FileRepositoryBuilder().setWorkTree(currentFile.getAbsoluteFile()).setGitDir(gitDir).build();

                Git git = new Git(repository);
                git.branchCreate().setName(branchName).call();
                git.close();

                JOptionPane.showMessageDialog(gui, "Successfully Created Branch", "Branch Creation Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException | GitAPIException e) {
                showErrorMessage("An error occurred during the branch creation process.", "Branch Creation Error");
            }
        }

        gui.repaint();
    }
//    private void branchCreateButton(){
//        if (currentFile == null) {
//            showErrorMessage("No location selected for creating branch.", "Select Location");
//            return;
//        }
//
//       // if the directory doesn't use git, can't use git command.
//        File gitDir = findGitDir(currentFile.getAbsoluteFile());
//        if (gitDir == null) {
//            showErrorMessage("This directory doesn't use git. Press init Button first.","No Git Directory");
//            return; // Exit the method without creating branchCreatePanel.
//        }
//        // to separate ui and model to reopen the commit button.
//        JPanel branchCreatePanel = createBranchCreatePanel();
//
//        int result =
//                JOptionPane.showConfirmDialog(
//                        gui, commitPanel, "Commit Changes", JOptionPane.OK_CANCEL_OPTION);
//
//        if(result == JOptionPane.OK_OPTION){
//            try {
//                String file = currentFile.getName();
//                String path = currentFile.getParent();
//                Process p;
//                String cmd = "cd " + path + " && git branch " + branchName;
//                String[] command = {"/bin/sh", "-c", cmd};
//                p = Runtime.getRuntime().exec(command);
//
//                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
//
//                String line = reader.readLine();
//                if(line != null){
//                    showErrorMessage(line, "Same Branch Name");
//                }
//
//
//
//            } catch (Throwable t) {
//                showThrowable(t);
//            }
//
//        }
////        String branchName =
////                JOptionPane.showInputDialog(gui,
////                        "Text a new branch name.");
////
////        if(branchName != null) {
////            try {
////                String file = currentFile.getName();
////                String path = currentFile.getParent();
////                Process p;
////                String cmd = "cd " + path + " && git branch " + branchName;
////                String[] command = {"/bin/sh", "-c", cmd};
////                p = Runtime.getRuntime().exec(command);
////
////                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
////
////                String line = reader.readLine();
////                if(line != null){
////                    showErrorMessage(line, "Same Branch Name");
////                }
////
////
////
////            } catch (Throwable t) {
////                showThrowable(t);
////            }
////        }
//
//        gui.repaint();
//    }

    private JPanel createBranchCreatePanel() {
        JPanel branchCreatePanel = new JPanel(new BorderLayout(3, 3));

        JTextField branchNameField = new JTextField(30);
        branchCreatePanel.add(new JLabel("Branch Name"), BorderLayout.NORTH);
        branchCreatePanel.add(branchNameField, BorderLayout.CENTER);

        branchCreatePanel.putClientProperty("branchNameField", branchNameField);

        return branchCreatePanel;
    }
    /**
     * Add the files that are contained within the directory of this node. Thanks to Hovercraft Full
     * Of Eels.
     */
    private void showChildren(final DefaultMutableTreeNode node) {
        tree.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);

        SwingWorker<Void, File> worker =
                new SwingWorker<Void, File>() {
                    @Override
                    public Void doInBackground() {
                        File file = (File) node.getUserObject();
                        if (file.isDirectory()) {
                            File[] files = fileSystemView.getFiles(file, true); // !!
                            if (node.isLeaf()) {
                                for (File child : files) {
                                    if (child.isDirectory()) {
                                        publish(child);
                                    }
                                }
                            }
                            setTableData(files);
                        }
                        return null;
                    }

                    @Override
                    protected void process(List<File> chunks) {
                        for (File child : chunks) {
                            node.add(new DefaultMutableTreeNode(child));
                        }
                    }

                    @Override
                    protected void done() {
                        progressBar.setIndeterminate(false);
                        progressBar.setVisible(false);
                        tree.setEnabled(true);
                    }
                };
        worker.execute();
    }

    /**
     * Update the File details view with the details of this File.
     */
    private void setFileDetails(File file) {
        currentFile = file;
        Icon icon = fileSystemView.getSystemIcon(file);
        fileName.setIcon(icon);
        fileName.setText(fileSystemView.getSystemDisplayName(file));
        path.setText(file.getPath());
        date.setText(new Date(file.lastModified()).toString());
        size.setText(file.length() + " bytes");
        readable.setSelected(file.canRead());
        writable.setSelected(file.canWrite());
        executable.setSelected(file.canExecute());
        isDirectory.setSelected(file.isDirectory());

        isFile.setSelected(file.isFile());


        JFrame f = (JFrame) gui.getTopLevelAncestor();
        if (f != null) {
            f.setTitle(APP_TITLE + " :: " + fileSystemView.getSystemDisplayName(file));
        }

        gui.repaint();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                new Runnable() {
                    public void run() {
                        try {
                            // Significantly improves the look of the output in
                            // terms of the file names returned by FileSystemView!
                            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                        } catch (Exception weTried) {
                        }
                        JFrame f = new JFrame(APP_TITLE);
                        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

                        FileManager fileManager = new FileManager();
                        f.setContentPane(fileManager.getGui());

                        try {
                            URL urlBig = fileManager.getClass().getResource("fm-icon-32x32.png");
                            URL urlSmall = fileManager.getClass().getResource("fm-icon-16x16.png");
                            ArrayList<Image> images = new ArrayList<Image>();
                            images.add(ImageIO.read(urlBig));
                            images.add(ImageIO.read(urlSmall));
                            f.setIconImages(images);
                        } catch (Exception weTried) {
                        }

                        f.pack();
                        f.setLocationByPlatform(true);
                        f.setMinimumSize(f.getSize());
                        f.setVisible(true);

                        fileManager.showRootFile();
                    }
                });
    }
}

/**
 * A TableModel to hold File[].
 */
class FileTableModel extends AbstractTableModel {

    private File[] files;
    private FileSystemView fileSystemView = FileSystemView.getFileSystemView();
    private String[] columns = {
            "Icon", "File", "Path/name", "Size", "Last Modified", "status",
    };

    FileTableModel() {
        this(new File[0]);
    }

    FileTableModel(File[] files) {
        this.files = files;
    }

    private File findGitDir(File directory) {
        File gitDir = new File(directory, ".git");
        if (gitDir.exists() && gitDir.isDirectory()) {
            return gitDir;
        } else {
            File parent = directory.getParentFile();
            return parent != null ? findGitDir(parent) : null;
        }
    }


    public Object getValueAt(int row, int column) {
        File file = files[row];

        switch (column) {
            case 0:
                return fileSystemView.getSystemIcon(file);
            case 1:
                return fileSystemView.getSystemDisplayName(file);
            case 2:
                return file.getPath();
            case 3:
                return file.length();
            case 4:
                return file.lastModified();
            case 5:
                try {
                    File gitDir = findGitDir(file.getAbsoluteFile());
                    if (gitDir == null) {
                        return "none";
                    } else if (gitDir != null && file.isDirectory()) {
                        return "gitDir";
                    }

                    String path = file.getParent();
                    String name = file.getName();

                    String cmd = "cd " + path + " && " + "git status -s " + name;
                    Process p;

                    String[] command = {"/bin/sh", "-c", cmd};
                    p = Runtime.getRuntime().exec(command);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

                    // 출력 결과를 저장할 문자열 버퍼 생성
                    StringBuilder output = new StringBuilder();

                    // 한 줄씩 출력 결과를 읽어와 버퍼에 추가
                    String line;
                    if ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    } else {
                        return "C";
                    }

                    String status = output.toString().substring(0, 2);
                    return status;

                } catch (Exception e) {
                    e.printStackTrace();
                    return "Error";
                }
            default:
                System.err.println("Logic Error");
        }
        return "";
    }

    public int getColumnCount() {
        return columns.length;
    }

    public Class<?> getColumnClass(int column) {
        switch (column) {
            case 0:
                return ImageIcon.class;
            case 3:
                return Long.class;
            case 4:
                return Date.class;
            case 5:
                return String.class;
        }
        return String.class;
    }

    public String getColumnName(int column) {
        return columns[column];
    }

    public int getRowCount() {
        return files.length;
    }

    public File getFile(int row) {
        return files[row];
    }

    public void setFiles(File[] files) {
        this.files = files;
        fireTableDataChanged();
    }
}

/**
 * A TreeCellRenderer for a File.
 */
class FileTreeCellRenderer extends DefaultTreeCellRenderer {

    private FileSystemView fileSystemView;

    private JLabel label;

    FileTreeCellRenderer() {
        label = new JLabel();
        label.setOpaque(true);
        fileSystemView = FileSystemView.getFileSystemView();
    }

    @Override
    public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus) {

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        File file = (File) node.getUserObject();
        label.setIcon(fileSystemView.getSystemIcon(file));
        label.setText(fileSystemView.getSystemDisplayName(file));
        label.setToolTipText(file.getPath());

        if (selected) {
            label.setBackground(backgroundSelectionColor);
            label.setForeground(textSelectionColor);
        } else {
            label.setBackground(backgroundNonSelectionColor);
            label.setForeground(textNonSelectionColor);
        }

        return label;
    }
}
