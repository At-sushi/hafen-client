/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.util.function.*;
import java.util.concurrent.atomic.*;

public class Loader {
    private final double timeout = 5.0;
    private final int maxthreads = 4;
    private final Queue<Future<?>> queue = new LinkedList<>();
    private final Collection<Thread> pool = new ArrayList<>();
    private final AtomicInteger busy = new AtomicInteger(0);

    public class Future<T> {
	public final Supplier<T> task;
	private final boolean capex;
	private T val;
	private Throwable exc;
	private Thread running = null;
	private boolean done = false, cancelled = false;

	private Future(Supplier<T> task, boolean capex) {
	    this.task = task;
	    this.capex = capex;
	}

	private void run() {
	    synchronized(this) {
		if(running != null) throw(new AssertionError());
		running = Thread.currentThread();
	    }
	    try {
		busy.getAndIncrement();
		try {
		    while(true) {
			try {
			    synchronized(this) {
				if(cancelled)
				    break;
			    }
			    T val = task.get();
			    synchronized(this) {
				this.val = val;
				done = true;
				break;
			    }
			} catch(Loading l) {
			    /* XXX: Make nonblocking */
			    l.waitfor();
			}
		    }
		} catch(InterruptedException e) {
		    if(!cancelled) {
			synchronized(queue) {
			    queue.add(this);
			    queue.notify();
			}
			Thread.currentThread().interrupt();
		    }
		} catch(Throwable exc) {
		    synchronized(this) {
			this.exc = exc;
			done = true;
		    }
		    if(!capex)
			throw(exc);
		}
	    } finally {
		synchronized(this) {
		    running = null;
		}
		busy.getAndDecrement();
	    }
	}

	public boolean cancel() {
	    synchronized(this) {
		cancelled = true;
		if(running != null)
		    running.interrupt();
		return(!done);
	    }
	}

	public T get() {
	    synchronized(this) {
		if(cancelled)
		    throw(new IllegalStateException("cancelled future"));
		if(!done)
		    throw(new IllegalStateException("not done"));
		if(exc != null)
		    throw(new RuntimeException("Deferred error in loader task", exc));
		return(val);
	    }
	}

	public boolean done() {
	    synchronized(this) {
		return(done || cancelled);
	    }
	}
    }

    private void loop() {
	try {
	    main: while(true) {
		Future<?> item;
		synchronized(queue) {
		    double start = Utils.rtime(), now = start;
		    while(true) {
			if(Thread.interrupted())
			    throw(new InterruptedException());
			if((item = queue.poll()) != null)
			    break;
			if((now - start) >= timeout)
			    break main;
			queue.wait((long)((timeout - (now - start)) * 1000) + 100);
			now = Utils.rtime();
		    }
		}
		item.run();
	    }
	} catch(InterruptedException e) {
	} finally {
	    synchronized(queue) {
		pool.remove(Thread.currentThread());
	    }
	}
	check();
    }

    private void check() {
	synchronized(queue) {
	    if((queue.size() > pool.size()) && (pool.size() < maxthreads)) {
		Thread th = new HackThread(this::loop, "Loader thread");
		th.setDaemon(true);
		th.start();
		pool.add(th);
	    }
	}
    }

    public <T> Future<T> defer(Supplier<T> task, boolean capex) {
	Future<T> ret = new Future<T>(task, capex);
	synchronized(queue) {
	    queue.add(ret);
	    queue.notify();
	}
	check();
	return(ret);
    }

    public <T> Future<T> defer(Supplier<T> task) {
	return(defer(task, true));
    }

    public <T> Future<T> defer(Runnable task, T result) {
	return(defer(() -> {
		    task.run();
		    return(result);
		}, false));
    }

    public String stats() {
	synchronized(queue) {
	    return(String.format("%d %d/%d", queue.size(), busy.get(), pool.size()));
	}
    }
}
