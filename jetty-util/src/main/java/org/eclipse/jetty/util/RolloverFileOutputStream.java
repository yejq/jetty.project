//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util; 

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

/** 
 * RolloverFileOutputStream.
 *
 * <p>
 * This output stream puts content in a file that is rolled over every 24 hours.
 * The filename must include the string "yyyy_mm_dd", which is replaced with the 
 * actual date when creating and rolling over the file.
 * </p>
 * <p>
 * Old files are retained for a number of days before being deleted.
 * </p>
 */
public class RolloverFileOutputStream extends FilterOutputStream
{
    private static Timer __rollover;
    
    final static String YYYY_MM_DD="yyyy_mm_dd";
    final static String ROLLOVER_FILE_DATE_FORMAT = "yyyy_MM_dd";
    final static String ROLLOVER_FILE_BACKUP_FORMAT = "HHmmssSSS";
    final static int ROLLOVER_FILE_RETAIN_DAYS = 31;

    private RollTask _rollTask;
    private ZonedDateTime midnight;
    private SimpleDateFormat _fileBackupFormat;
    private SimpleDateFormat _fileDateFormat;

    private String _filename;
    private File _file;
    private boolean _append;
    private int _retainDays;
    
    /* ------------------------------------------------------------ */
    /**
     * @param filename The filename must include the string "yyyy_mm_dd", 
     * which is replaced with the actual date when creating and rolling over the file.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename)
        throws IOException
    {
        this(filename,true,ROLLOVER_FILE_RETAIN_DAYS);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param filename The filename must include the string "yyyy_mm_dd", 
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename, boolean append)
        throws IOException
    {
        this(filename,append,ROLLOVER_FILE_RETAIN_DAYS);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filename The filename must include the string "yyyy_mm_dd", 
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them.  0 to retain forever.
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays)
        throws IOException
    {
        this(filename,append,retainDays,TimeZone.getDefault());
    }

    /* ------------------------------------------------------------ */
    /**
     * @param filename The filename must include the string "yyyy_mm_dd", 
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them. 0 to retain forever.
     * @param zone the timezone for the output
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays,
                                    TimeZone zone)
        throws IOException
    {

         this(filename,append,retainDays,zone,null,null);
    }
     
    /* ------------------------------------------------------------ */
    /**
     * @param filename The filename must include the string "yyyy_mm_dd", 
     * which is replaced with the actual date when creating and rolling over the file.
     * @param append If true, existing files will be appended to.
     * @param retainDays The number of days to retain files before deleting them. 0 to retain forever.
     * @param zone the timezone for the output
     * @param dateFormat The format for the date file substitution. The default is "yyyy_MM_dd". 
     * @param backupFormat The format for the file extension of backup files. The default is "HHmmssSSS". 
     * @throws IOException if unable to create output
     */
    public RolloverFileOutputStream(String filename,
                                    boolean append,
                                    int retainDays,
                                    TimeZone zone,
                                    String dateFormat,
                                    String backupFormat)
        throws IOException
    {
        super(null);

        if (dateFormat==null)
            dateFormat=ROLLOVER_FILE_DATE_FORMAT;
        _fileDateFormat = new SimpleDateFormat(dateFormat);
        
        if (backupFormat==null)
            backupFormat=ROLLOVER_FILE_BACKUP_FORMAT;
        _fileBackupFormat = new SimpleDateFormat(backupFormat);
        
        _fileBackupFormat.setTimeZone(zone);
        _fileDateFormat.setTimeZone(zone);
        
        if (filename!=null)
        {
            filename=filename.trim();
            if (filename.length()==0)
                filename=null;
        }
        if (filename==null)
            throw new IllegalArgumentException("Invalid filename");

        _filename=filename;
        _append=append;
        _retainDays=retainDays;
        setFile();
        
        synchronized(RolloverFileOutputStream.class)
        {
            if (__rollover==null)
                __rollover=new Timer(RolloverFileOutputStream.class.getName(),true);
            
            _rollTask=new RollTask();

            midnight = toMidnight(ZonedDateTime.now(), zone.toZoneId());
            
            scheduleNextRollover();
        }
    }
    
    /**
     * Get the "start of day" for the provided DateTime at the zone specified.
     *
     * @param dateTime the date time to calculate from
     * @param zone the zone to return the date in
     * @return start of the day of the date provided
     */
    public static ZonedDateTime toMidnight(ZonedDateTime dateTime, ZoneId zone)
    {
        return dateTime.toLocalDate().atStartOfDay(zone);
    }
    
    /**
     * Get the next "start of day" for the provided date.
     *
     * @param dateTime the date to calculate from
     * @return the start of the next day
     */
    public static ZonedDateTime nextMidnight(ZonedDateTime dateTime)
    {
        // Increment to next day.
        // Using Calendar.add(DAY, 1) takes in account Daylights Savings
        // differences, and still maintains the "midnight" settings for
        // Hour, Minute, Second, Milliseconds
        return dateTime.toLocalDate().plus(1, ChronoUnit.DAYS).atStartOfDay(dateTime.getZone());
    }
    
    private void scheduleNextRollover()
    {
        midnight = nextMidnight(midnight);
        __rollover.schedule(_rollTask,midnight.toInstant().toEpochMilli() - System.currentTimeMillis());
    }

    /* ------------------------------------------------------------ */
    public String getFilename()
    {
        return _filename;
    }
    
    /* ------------------------------------------------------------ */
    public String getDatedFilename()
    {
        if (_file==null)
            return null;
        return _file.toString();
    }
    
    /* ------------------------------------------------------------ */
    public int getRetainDays()
    {
        return _retainDays;
    }

    /* ------------------------------------------------------------ */
    private synchronized void setFile()
        throws IOException
    {
        // Check directory
        File file = new File(_filename);
        _filename=file.getCanonicalPath();
        file=new File(_filename);
        File dir= new File(file.getParent());
        if (!dir.isDirectory() || !dir.canWrite())
            throw new IOException("Cannot write log directory "+dir);
            
        Date now=new Date();
        
        // Is this a rollover file?
        String filename=file.getName();
        int i=filename.toLowerCase(Locale.ENGLISH).indexOf(YYYY_MM_DD);
        if (i>=0)
        {
            file=new File(dir,
                          filename.substring(0,i)+
                          _fileDateFormat.format(now)+
                          filename.substring(i+YYYY_MM_DD.length()));
        }
            
        if (file.exists()&&!file.canWrite())
            throw new IOException("Cannot write log file "+file);

        // Do we need to change the output stream?
        if (out==null || !file.equals(_file))
        {
            // Yep
            _file=file;
            if (!_append && file.exists())
                file.renameTo(new File(file.toString()+"."+_fileBackupFormat.format(now)));
            OutputStream oldOut=out;
            out=new FileOutputStream(file.toString(),_append);
            if (oldOut!=null)
                oldOut.close();
            //if(log.isDebugEnabled())log.debug("Opened "+_file);
        }
    }

    /* ------------------------------------------------------------ */
    private void removeOldFiles()
    {
        if (_retainDays>0)
        {
            ZonedDateTime now = ZonedDateTime.now(this.midnight.getZone());
            now.minus(_retainDays, ChronoUnit.DAYS);
            long expired = now.toInstant().toEpochMilli();
            
            File file= new File(_filename);
            File dir = new File(file.getParent());
            String fn=file.getName();
            int s=fn.toLowerCase(Locale.ENGLISH).indexOf(YYYY_MM_DD);
            if (s<0)
                return;
            String prefix=fn.substring(0,s);
            String suffix=fn.substring(s+YYYY_MM_DD.length());

            String[] logList=dir.list();
            for (int i=0;i<logList.length;i++)
            {
                fn = logList[i];
                if(fn.startsWith(prefix)&&fn.indexOf(suffix,prefix.length())>=0)
                {        
                    File f = new File(dir,fn);
                    if(f.lastModified() < expired)
                    {
                        f.delete();
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void write (byte[] buf)
            throws IOException
     {
            out.write (buf);
     }

    /* ------------------------------------------------------------ */
    @Override
    public void write (byte[] buf, int off, int len)
            throws IOException
     {
            out.write (buf, off, len);
     }
    
    /* ------------------------------------------------------------ */
    @Override
    public void close()
        throws IOException
    {
        synchronized(RolloverFileOutputStream.class)
        {
            try{super.close();}
            finally
            {
                out=null;
                _file=null;
            }

            _rollTask.cancel(); 
        }
    }
    
    /* ------------------------------------------------------------ */
    private class RollTask extends TimerTask
    {
        @Override
        public void run()
        {
            try
            {
                RolloverFileOutputStream.this.setFile();
                RolloverFileOutputStream.this.scheduleNextRollover();
                RolloverFileOutputStream.this.removeOldFiles();
            }
            catch(IOException e)
            {
                // Cannot log this exception to a LOG, as RolloverFOS can be used by logging
                e.printStackTrace(System.err);
            }
        }
    }
}
