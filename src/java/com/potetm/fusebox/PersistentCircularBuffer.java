package com.potetm.fusebox;

import clojure.lang.*;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

public class PersistentCircularBuffer extends AFn
        implements
        Counted,
        IHashEq,
        IMeta,
        IObj,
        Indexed,
        IPersistentCollection,
        IPersistentVector,
        Associative,
        ILookup,
        IFn,
        Seqable,
        Reversible,
        Sequential,
        Iterable,
        Collection {

    private static final Keyword NIL = Keyword.intern("com.potetm.fusebox", "nil");
    private final IPersistentVector vec;
    private final int size;
    private final int idx;
    private int _hash;
    private int _hasheq;
    private final IPersistentMap _meta;
    private PersistentCircularBuffer empty;

    public PersistentCircularBuffer(int size) {
        this(PersistentVector.EMPTY, size, 0, null);
    }

    public PersistentCircularBuffer(int size, IPersistentMap _meta) {
        this(PersistentVector.EMPTY, size, 0, _meta);
    }

    public PersistentCircularBuffer(IPersistentVector vec,
                                    int size,
                                    int idx,
                                    IPersistentMap _meta) {
        if (vec == null) vec = PersistentVector.EMPTY;

        if (vec.count() < size) {
            for (int i = vec.count(); i < size; i++) {
                vec = vec.cons(NIL);
            }
        } else if (vec.count() > size) {
            throw new IllegalArgumentException("vector cannot be greater than buffer size");
        }
        assert vec.count() == size;
        this.vec = vec;
        this.size = size;
        this.idx = idx;
        this._meta = _meta;
    }

    private int mod(int n, int d) {
        int m = n % d;
        if (m == 0 || (n > 0) == (d > 0))
            return m;
        return m + d;
    }

    private int index(int i) {
        if (size == 0)
            return 0;
        return mod((idx + i), size);
    }

    private int nextIdx() {
        return index(1);
    }

    @Override
    public IPersistentVector cons(Object o) {
        int nxt = nextIdx();
        return new PersistentCircularBuffer(vec.assocN(nxt, o), size, nxt, _meta);
    }

    @Override
    public Object nth(int i) {
        return vec.nth(index(i));
    }

    @Override
    public Object nth(int i, Object notFound) {
        return vec.nth(index(i));
    }


    @Override
    public boolean containsKey(Object key) {
        return Util.isInteger(key);
    }

    @Override
    public IMapEntry entryAt(Object key) {
        if (containsKey(key))
            return new MapEntry(key, nth(RT.intCast(key)));
        return null;
    }

    @Override
    public Associative assoc(Object key, Object val) {
        if (Util.isInteger(key))
            return assocN(RT.intCast(key), val);
        throw new IllegalArgumentException("Key must be an integer!");
    }

    @Override
    public int count() {
        return size;
    }

    @Override
    public Object invoke(Object o) {
        if (containsKey(o)) {
            return nth(RT.intCast(o));
        }
        throw new IllegalArgumentException("Key must be an integer!");
    }


    @Override
    public Object valAt(Object key) {
        if (containsKey(key))
            return nth(RT.intCast(key));
        return null;
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (containsKey(key))
            return nth(RT.intCast(key));
        return notFound;
    }

    public int length() {
        return size;
    }

    @Override
    public IPersistentVector assocN(int i, Object val) {
        return new PersistentCircularBuffer(
                vec.assocN(index(i), val),
                size,
                idx,
                _meta);
    }

    @Override
    public IPersistentCollection empty() {
        PersistentCircularBuffer pcb = empty;

        if (pcb == null) {
            pcb = new PersistentCircularBuffer(size);
            empty = pcb;
        }

        return pcb;
    }

    @Override
    public Object peek() {
        return vec.nth(idx);
    }

    @Override
    public IPersistentStack pop() {
        return new PersistentCircularBuffer(vec.assocN(idx, NIL), size, idx - 1, _meta);
    }

    @Override
    public ISeq rseq() {
        if (size > 0)
            return new RSeq(this, 0);
        return null;
    }

    @Override
    public ISeq seq() {
        if (size > 0)
            return new Seq(this, 0);
        return null;
    }

    @Override
    public Iterator<Object> iterator() {
        return new Iter(this);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        for (ISeq s = seq(); s != null; s = s.next()) {
            if (s.first() != NIL) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean contains(Object o) {
        for (ISeq s = seq(); s != null; s = s.next()) {
            if (Util.equiv(s.first(), o))
                return true;
        }
        return false;
    }

    @Override
    public Object[] toArray() {
        Object[] ret = new Object[size];
        for (int i = 0; i < size; i++)
            ret[i] = nth(i);
        return ret;
    }

    @Override
    public boolean containsAll(Collection coll) {
        for (Object c : coll) {
            if (!contains(c)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Object[] toArray(Object[] a) {
        return RT.seqToPassedArray(seq(), a);
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        return new PersistentCircularBuffer(vec, size, idx, meta);
    }

    @Override
    public IPersistentMap meta() {
        return _meta;
    }

    @Override
    public boolean equiv(Object o) {
        return equals(o);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersistentCircularBuffer that = (PersistentCircularBuffer) o;

        return idx == that.idx &&
                Objects.equals(vec, that.vec);
    }

    @Override
    public int hasheq() {
        int heq = _hasheq;
        if (heq == 0) {
            heq = Util.hashCombine(Util.hasheq(vec), Murmur3.hashLong(idx));
            _hasheq = heq;
        }
        return heq;
    }

    @Override
    public int hashCode() {
        int h = _hash;
        if (h == 0) {
            h = Objects.hash(vec, idx);
            _hash = h;
        }
        return h;
    }


    @Override
    public boolean add(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection c) {
        throw new UnsupportedOperationException();
    }

    private static class Iter implements Iterator<Object> {
        private final PersistentCircularBuffer cb;
        private int idx = 0;

        public Iter(PersistentCircularBuffer cb) {
            this.cb = cb;
        }

        @Override
        public boolean hasNext() {
            return idx < cb.size;
        }

        @Override
        public Object next() {
            return cb.nth(idx++);
        }
    }

    private static class Seq extends ASeq implements IndexedSeq, Counted, IReduce {
        private final PersistentCircularBuffer cb;
        private final int i;

        public Seq(PersistentCircularBuffer cb, int i) {
            this.cb = cb;
            this.i = i;
        }

        public Seq(IPersistentMap meta, PersistentCircularBuffer cb, int i) {
            super(meta);
            this.cb = cb;
            this.i = i;
        }

        @Override
        public Object first() {
            return cb.nth(i);
        }

        @Override
        public ISeq next() {
            if (i + 1 < cb.size)
                return new Seq(cb, i + 1);
            return null;
        }

        @Override
        public Obj withMeta(IPersistentMap meta) {
            return new Seq(meta, cb, i);
        }

        @Override
        public int index() {
            return i;
        }

        @Override
        public int count() {
            return cb.size - i;
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = cb.nth(i);
            for (int x = i + 1; x < cb.size; x++) {
                ret = f.invoke(ret, cb.nth(x));
                if (RT.isReduced(ret))
                    return ((IDeref) ret).deref();
            }
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, cb.nth(i));
            for (int x = i + 1; x < cb.size; x++) {
                if (RT.isReduced(ret))
                    return ((IDeref) ret).deref();
                ret = f.invoke(ret, cb.nth(x));
            }
            if (RT.isReduced(ret))
                return ((IDeref) ret).deref();
            return ret;
        }
    }


    public static class RSeq extends ASeq implements IndexedSeq, Counted, IReduce {
        final PersistentCircularBuffer cb;
        final int i;

        public RSeq(PersistentCircularBuffer cb, int i) {
            this.cb = cb;
            this.i = i;
        }

        public RSeq(IPersistentMap meta, PersistentCircularBuffer cb, int i) {
            super(meta);
            this.cb = cb;
            this.i = i;
        }

        public Object first() {
            return cb.nth(i);
        }

        public ISeq next() {
            if (i - 1 > -cb.size)
                return new RSeq(cb, i - 1);
            return null;
        }

        public int index() {
            return i;
        }

        public int count() {
            return cb.size + i;
        }

        public RSeq withMeta(IPersistentMap meta) {
            if (meta() == meta)
                return this;
            return new RSeq(meta, cb, i);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = cb.nth(i);
            for (int x = i - 1; x > -cb.size; x--) {
                ret = f.invoke(ret, cb.nth(x));
                if (RT.isReduced(ret))
                    return ((IDeref) ret).deref();
            }
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, cb.nth(i));
            for (int x = i - 1; x > -cb.size; x--) {
                if (RT.isReduced(ret))
                    return ((IDeref) ret).deref();
                ret = f.invoke(ret, cb.nth(x));
            }
            if (RT.isReduced(ret))
                return ((IDeref) ret).deref();
            return ret;
        }
    }
}
