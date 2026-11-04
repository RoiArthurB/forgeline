# tm4e's native Oniguruma searcher holds its regexes (regExps) only to keep them alive: it searches by their native
# pointers, and each regex's finalizer frees its native one. Batch search being always on, R8 sees the list never read
# and drops it, so a GC (images loading beside a README's code blocks) frees regexes mid-search and onig_search
# crashes on freed memory. Keep the native wrappers whole.
-keep class org.eclipse.tm4e.core.internal.oniguruma.impl.onig.** { *; }
