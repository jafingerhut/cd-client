(ns cd-client.core
  (:use [clojure.java.browse :only [browse-url]]
        [clojure.pprint :only [pprint]])
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.reader.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl :as repl]))


(def ^:private ^:dynamic *screen-width* 72)


;; Use set-local-mode! to load in a new snapshot file.
(def ^:private ^:dynamic *cd-client-mode* (atom {}))


(defn- read-safely [x & opts]
  (with-open [r (java.io.PushbackReader. (apply io/reader x opts))]
    (edn/read r)))


(defn data-from-snapshot-file-format-v0 [fname]
  (let [x (read-safely fname)]
    {:filename fname, :data (:snapshot-info x),
     :snapshot-time (:snapshot-time x)}))


;; Handle errors in attempting to open the file, or as returned from
;; read?
(defn set-local-mode!
  "Change the behavior of future calls to cdoc and other documentation
  access functions and macros in cd-client.core. Instead of using the
  Internet, their results will be obtained from a file. This can be
  useful when you do not have Internet access. Even if you do have
  access, you may be able to get results more quickly in local mode,
  and you will not put load on the clojuredocs.org servers.

  A snapshot file is available here:

  https://raw.github.com/jafingerhut/cd-client/develop/snapshots/clojuredocs-snapshot-latest.txt

  Example:

  user=> (set-local-mode! \"snapshots/clojuredocs-snapshot-latest.txt\")
  Read info on 3574 names from file: snapshots/clojuredocs-snapshot-latest.txt
  Snapshot time: Sun Apr 01 17:20:17 PDT 2012
  nil"
  [fname]
  (let [snapshot-info (data-from-snapshot-file-format-v0 fname)
        data (:data snapshot-info)
        snapshot-time (:snapshot-time snapshot-info)]
    (reset! *cd-client-mode* snapshot-info)
    (println "Read info on" (count data) "names from file:" fname)
    (println "Snapshot time:" snapshot-time)))

(set-local-mode! "snapshots/clojuredocs-snapshot-latest.txt")

(defn show-mode
  "Print brief info about the snapshot file that has been loaded.

  See also: set-local-mode!"
  []
  (let [{:keys [data filename snapshot-time]} @*cd-client-mode*]
    (println "Data for" (count data)
             "names was read from file:" filename)
    (println "Snapshot time:" snapshot-time)))


(defn- remove-markdown
  "Remove basic markdown syntax from a string."
  [text]
  (-> text
      (.replaceAll "<pre>" "\n")
      (.replaceAll "</pre>" "\n")
      (.replaceAll "<code>" "")
      (.replaceAll "</code>" "")
      (.replaceAll "<b>" "")
      (.replaceAll "</b>" "")
      (.replaceAll "<p>" "")
      (.replaceAll "</p>" "")
      (.replaceAll "&gt;" ">")
      (.replaceAll "&lt;" "<")
      (.replaceAll "&amp;" "&")
      (.replaceAll "<br>" "")
      (.replaceAll "<br/>" "")
      (.replaceAll "<br />" "")
      (.replaceAll "\\\\r\\\\n" "\\\\n")))


(defn- vec-drop-last-while
  "Like drop-while, but only for vectors, and only removes consecutive
  items from the end that make (pred item) true. Unlike drop-while,
  items for which (pred item) returns nil or false will be removed."
  [pred v]
  (loop [i (dec (count v))]
    (if (neg? i)
      []
      (if (pred (v i))
        (recur (dec i))
        (subvec v 0 (inc i))))))


(defn wrap-line
  "Given a string 'line' that is assumed not contain line separators,
  but may contain spaces and tabs, return a sequence of strings where
  each is at most width characters long, and all 'words' (consecutive
  sequences of non-whitespace characters) are kept together in the
  same line. The only exception to the maximum width are if a single
  word is longer than width, in which case it is kept together on one
  line. Whitespace in the original string is kept except it is
  removed from the end and where lines are broken. As a special case,
  any whitespace before the first word is preserved. The second and
  all later lines will always begin with a non-whitespace character."
  [line width]
  (let [space-plus-words (map first (re-seq #"(\s*\S+)|(\s+)"
                                            (string/trimr line)))]
    (loop [finished-lines []
           partial-line []
           len 0
           remaining-words (seq space-plus-words)]
      (if-let [word (first remaining-words)]
        (if (zero? len)
          ;; Special case for first word of first line. Keep it as
          ;; is, including any leading whitespace it may have.
          (recur finished-lines [ word ] (count word) (rest remaining-words))
          (let [word-len (count word)
                len-if-append (+ len word-len)]
            (if (<= len-if-append width)
              (recur finished-lines (conj partial-line word) len-if-append
                     (rest remaining-words))
              ;; else we're done with current partial-line and need to
              ;; start a new one. Trim leading whitespace from word,
              ;; which will be the first word of the next line.
              (let [trimmed-word (string/triml word)]
                (recur (conj finished-lines (apply str partial-line))
                       [ trimmed-word ]
                       (count trimmed-word)
                       (rest remaining-words))))))
        (if (zero? len)
          [ "" ]
          (conj finished-lines (apply str partial-line)))))))


(defn- trim-line-list
  "Break string s into lines, then remove any lines at the beginning
  and end that are all whitespace. Keep lines in the middle that are
  completely whitespace, if any. This is to help reduce the amount of
  unneeded whitespace when printing examples."
  [s]
  (let [lines (string/split s #"\n")
        lines (vec (drop-while string/blank? lines))]
    (vec-drop-last-while string/blank? lines)))


(defn call-with-ns-and-name
  [f v]
  (let [m (meta v)
        ns (str (.name (:ns m)))
        name (str (:name m))]
    (f ns name)))


(defmacro handle-fns-etc
  [name fn]
  (if (special-symbol? `~name)
    `(~fn "clojure.core" (str '~name))
    (let [nspace (find-ns name)]
      (if nspace
        `(println "No usage examples for namespaces as a whole like" '~name
                  "\nTry a particular symbol in a namespace,"
                  "e.g. clojure.string/join")
        `(call-with-ns-and-name ~fn (var ~name))))))

(defn examples-core
  "Return examples from clojuredocs for a given namespace and name (as strings)"
  [ns name]
  (let [{:keys [data]} @*cd-client-mode*]
    ;; Make examples-core return the value that I prefer when there
    ;; are no examples, i.e. the URL and an empty vector of
    ;; examples. Then I can test browse-to to see if it will work
    ;; unmodified for names that have no examples.
    (let [name-info (get data (str ns "/" name))]
      {:examples (:examples name-info),
       :url (:url name-info)})))


(defmacro examples
  "Return examples from clojuredocs for a given unquoted var, fn, macro,
  special form, or a namespace and name (as strings). Returns a map
  with a structure defined by the clojuredocs.org API.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name examples-core))
  ([ns name]
   `(examples-core ~ns ~name)))


;; Can we make pr-examples-core private, to avoid polluting namespace
;; of those who wish to use this namespace?  Trying defn- or defn
;; ^:private instead of defn for pr-examples-core, then this fails:
;;
;; (require '[cd-client.core :as c])
;; (c/pr-examples *)
;;
;; saying that cd-client.core/pr-examples-core is not public.

(defn pr-examples-core
  "Given a namespace and name (as strings), pretty-print all the
  examples for it from clojuredocs."
  [ns name & verbose]
  (let [res (examples-core ns name)
        n (count (:examples res))]
    (when (not= n 0) (println "========== vvv Examples ================"))
    (dotimes [i n]
      (let [ex (nth (:examples res) i)]
        (when (not= i 0)    ; this line is a separator between examples
          (println "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"))
        (println " " (string/join "\n  "
                                  (trim-line-list
                                   (remove-markdown (:body ex)))))
        (when verbose
          (println "  *** Last Updated:" (:updated_at ex)))))
    (when (not= n 0) (println "========== ^^^ Examples ================"))
    (printf "%d example%s found for %s"
            n (if (== 1 n) "" "s") (str ns "/" name))
    (println)
    (when verbose
      (println "Taken from" (:url res)))))


(defmacro pr-examples
  "Given an unquoted var, fn, macro, special form, or a namespace and
  name (as strings), pretty-print all the examples for it from
  clojuredocs.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
     `(handle-fns-etc ~name pr-examples-core))
  ([ns name]
     `(pr-examples-core ~ns ~name)))


(defn- search-local-core
  "Helper for search-core specific to local mode."
  [ns str-or-pattern data]
  (let [matches? (if (instance? java.util.regex.Pattern str-or-pattern)
                   #(re-find str-or-pattern (str %))
                   #(.contains (str %) (str str-or-pattern)))]
    (filter (fn [{symbol-ns :ns, symbol-name :name}]
              (and (or (nil? ns) (= symbol-ns ns))
                   (matches? symbol-name)))
            (vals data))))


(defn search-core
  "Handles either an exact substring match if str-or-pattern is a
  string, or a regex search if it is a regex. This string or pattern
  matching is restricted to the symbol name by itself, apart from its
  namespace name. ns can be nil indicating that the search should be
  performed in all namespaces, or it can be a string containing the
  name of a single namespace in which the search should be restricted.

  Returns sequence of maps, one per symbol found. Note that web site
  can return more than one such map for the same symbol, e.g. one for
  Clojure 1.2 and another for Clojure 1.3."
  [ns str-or-pattern]
  (let [{:keys [data]} @*cd-client-mode*]
    (search-local-core ns str-or-pattern data)))


(defn search
  "Search for symbols whose names contain a specified string or match a
  regex pattern.  With a single argument, all symbols in known
  namespaces are searched. With two arguments, only the namespace
  whose name is specified by the string ns is searched.

  Examples:

  (search \"split\")      ; symbol name contains 'split'
  (search \">\")          ; contains '>'
  (search \"clojure.core\" \"with\") ; contains 'with' and is in
                                     ; namespace clojure.core

  Examples regex pattern searches:

  (search \"clojure.core\" \"^with\") ; begins with 'with' and is in
                                      ; namespace clojure.core
  (search #\"sp[al]\")    ; symbol name contains 'sp' followed by 'a' or 'l'
  (search #\"let$\")      ; ends with 'let'
  (search #\"(?i)array\") ; symbol name contains 'array', matched
                          ; case-insensitively"
  ([str-or-pattern] (search nil str-or-pattern))
  ([ns str-or-pattern]
   (let [results (search-core ns str-or-pattern)
         fullnames (map #(str (:ns %) "/" (:name %)) results)
         fullnames-uniq (set fullnames)]
     (println (string/join "\n" (sort fullnames-uniq)))
     (println (count fullnames-uniq) "matches found"))))


(defn comments-core
  "Return comments from clojuredocs for a given namespace and name (as
  strings)"
  [ns name]
  (let [{:keys [data]} @*cd-client-mode*
        name-info (get data (str ns "/" name))]
    (:comments name-info)))


(defmacro comments
  "Return comments from clojuredocs for a given unquoted var, fn, macro,
  special form, or namespace and name (as strings). Returns nil if
  there are no comments, or a vector of maps with a structure defined
  by the clojuredocs.org API if there are comments.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name comments-core))
  ([ns name]
   `(comments-core ~ns ~name)))


(defn pr-comments-core
  "Given a namespace and name (as strings), pretty-print all the
  comments for it from clojuredocs"
  [ns name & verbose]
  (let [res (comments-core ns name)
        n (count res)]
    (when (not= n 0) (println "========== vvv Comments ================"))
    (dotimes [i n]
      (let [ex (nth res i)]
        (when (not= i 0)    ; this line is a separator between comments
          (println "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"))
        (println " " (string/join "\n  "
                                  (mapcat #(wrap-line % *screen-width*)
                                          (-> (remove-markdown (:body ex))
                                              (string/replace #"\r" "")
                                              (trim-line-list)))))
        (when verbose
          (println "  *** Last Updated:" (:updated_at ex)))))
    (when (not= n 0) (println "========== ^^^ Comments ================"))
    (printf "%d comment%s found for %s"
            n (if (== 1 n) "" "s") (str ns "/" name))
    (println)
    ;; no URL in comments yet
    #_(when verbose (println "Taken from" (:url res)))))


(defmacro pr-comments
  "Given an unquoted var, fn, macro, special form, or a namespace and
  name (as strings), pretty-print all the comments for it from
  clojuredocs.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name pr-comments-core))
  ([ns name]
   `(pr-comments-core ~ns ~name)))


(defn see-also-core
  "Return 'see also' info from clojuredocs for a given namespace and
  name (as strings)"
  [ns name]
  (let [{:keys [data]} @*cd-client-mode*
        name-info (get data (str ns "/" name))]
    (:see-alsos name-info)))


(defmacro see-also
  "Given an unquoted var, fn, macro, special form, or a namespace and
  name (as strings), show the 'see also' for it from clojuredocs.
  Returns nil if there are no 'see alsos', or a vector of maps with a
  structure defined by the clojuredocs.org API if there are comments.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name see-also-core))
  ([ns name]
   `(see-also-core ~ns ~name)))


(defn pr-see-also-core
  "Given a namespace and name (as strings), pretty-print all the
  see-alsos for it from clojuredocs"
  [ns name]
  (let [res (see-also-core ns name)
        n (count res)]
    (when (not= n 0) (println "========== vvv See also ================"))
    (doseq [sa res]
      ;; TBD: Add in namespace if and when it is added as part of the
      ;; see-also API results from the web site.
                                        ;(println " " (str (:ns sa) "/" (:name sa)))
      (println " " (:name sa)))
    (when (not= n 0) (println "========== ^^^ See also ================"))
    (printf "%d see-also%s found for %s"
            n (if (== 1 n) "" "s") (str ns "/" name))
    (println)))


(defmacro pr-see-also
  "Given an unquoted var, fn, macro, special form, or a namespace and
  name (as strings), pretty-print the 'see also' for it from
  clojuredocs.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name pr-see-also-core))
  ([ns name]
   `(pr-see-also-core ~ns ~name)))


(defn cdoc-core
  [ns name]
  (pr-examples-core ns name)
  (println)
  (pr-see-also-core ns name)
  (println)
  (pr-comments-core ns name))


(defmacro cdoc
  "Given an unquoted var, fn, macro, special form, or a namespace and
  name (as strings), show the Clojure documentation, and any examples,
  see also pointers, and comments available on clojuredocs.

  By default, cdoc and its related functions and macros in
  cd-client.core use the Internet to access clojuredocs.org at the
  time you invoke them. If you wish to work off line from a snapshot
  file, use set-local-mode!

  See also: set-local-mode! browse-to search

  Examples:

  (cdoc *)
  (cdoc catch)
  (cdoc ->>)

  Just as for clojure.repl/doc, you may name a var, fn, or macro using
  the full namespace/name, or any shorter version accessible due to
  prior calls to require, use, refer, etc.

  Thus the two cdoc calls below give the same result:

  (cdoc clojure.string/join)
  (require '[clojure.string :as str])
  (cdoc str/join)

  And all of the cdoc calls below give the same result:

  (cdoc \"clojure.java.io\" \"reader\")
  (cdoc clojure.java.io/reader)
  (use 'clojure.java.io)
  (cdoc reader)"
  ([name]
   `(do
      (repl/doc ~name)
      (handle-fns-etc ~name cdoc-core)))
  ([ns name]
   `(do
      (repl/doc ~(symbol ns name))
      (cdoc-core ~ns ~name))))


(defmacro cdir
  "Like clojure.repl/dir, except also print the number of examples,
  see-alsos, and comments that each symbol has in the snapshot data."
  [nsname]
  (printf "Exa See Com Symbol\n")
  (printf "--- --- --- -------------\n")
  `(doseq [v# (repl/dir-fn '~nsname)]
     (printf "%3d %3d %3d %s\n"
             (count (:examples (examples-core '~nsname v#)))
             (count (see-also-core '~nsname v#))
             (count (comments-core '~nsname v#))
             v#)))


(defn browse-to-core
  "Open a browser to the clojuredocs page for a given namespace and name
  (as strings)"
  ([ns name]
   (when-let [url (:url (examples-core ns name))]
     (browse-url url))))


(defmacro browse-to
  "Given an unquoted var, fn, macro, or special form, or a namespace
  and name (as strings), open a browser to the clojuredocs page for
  it.

  Note: This macro always attempts to access the Internet.

  See cdoc documentation for examples of the kinds of arguments that
  can be given. This macro can be given the same arguments as cdoc."
  ([name]
   `(handle-fns-etc ~name browse-to-core))
  ([ns name]
   `(browse-to-core ~ns ~name)))


;; ordering-map copied under Eclipse Public License v1.0 from useful
;; library available at: https://github.com/flatland/useful

(defn ordering-map
  "Create an empty map with a custom comparator that puts the given
keys first, in the order specified. Other keys will be placed after
the special keys, sorted by the default-comparator."
  ([key-order] (ordering-map key-order compare))
  ([key-order default-comparator]
   (let [indices (into {} (map-indexed (fn [i x] [x i]) key-order))]
     (sorted-map-by (fn [a b]
                      (if-let [a-idx (indices a)]
                        (if-let [b-idx (indices b)]
                          (compare a-idx b-idx)
                          -1)
                        (if (indices b)
                          1
                          (default-comparator a b))))))))

(def empty-ordered-one-name-info
  (ordering-map [
                 :name
                 :ns
                 :url
                 :id
                 :see-alsos
                 :examples
                 :comments
                 ]))

(def empty-ordered-see-also
  (ordering-map [
                 :name
                 :url_friendly_name
                 :url
                 :file
                 :version
                 :added
                 :created_at
                 :updated_at
                 :namespace_id
                 :weight
                 :line
                 :arglists_comp
                 ]))

(def empty-ordered-example
  (ordering-map [
                 :function
                 :ns
                 :library
                 :lib_version
                 :created_at
                 :updated_at
                 :namespace_id
                 :version
                 :library_id
                 :body
                 ]))

(def empty-ordered-comment
  (ordering-map [
                 :function
                 :ns
                 :library
                 :version
                 :user_id
                 :created_at
                 :updated_at
                 :namespace_id
                 :library_id
                 :body
                 ]))


(defn use-sorted-maps [all-info-map]
  (reduce (fn [m [name-str info]]
            (let [{:keys [comments see-alsos examples]} info
                  sorted-comments (->> comments
                                       (map #(into empty-ordered-comment %))
                                       (sort-by :created_at))
                  sorted-see-alsos (->> see-alsos
                                        (map #(into empty-ordered-see-also %))
                                        (sort-by :name))
                  sorted-examples (->> examples
                                       (map #(into empty-ordered-example %))
                                       (sort-by :created_at))
                  info-with-sorted-maps (assoc info
                                          :comments sorted-comments
                                          :see-alsos sorted-see-alsos
                                          :examples sorted-examples)]
              (assoc m
                name-str
                info-with-sorted-maps)))
          (sorted-map) all-info-map))


;; Collect lots of info about each name:
;; + examples, see also list, and comments
;; + DON'T get the Clojure documentation string. Assume we have that
;; locally already.

(defn make-snapshot
  "Create a snapshot file that can be read in with set-local-mode!

  Warning: Please consider using a snapshot file created by someone
  else, since doing so using this function can take a long time (over
  an hour), and if many people do so it could add significant load to
  the clojuredocs server.

  Examples:

  (make-snapshot \"\" \"clojuredocs-snapshot-2011-12-03.txt\")
  (make-snapshot \"let\" \"only-clojuredocs-symbols-containing-let.txt\")"
  [search-str fname & quiet]
  (let [verbose (not quiet)
        all-names-urls (search-core nil search-str)
        n (count all-names-urls)
        _ (when verbose
            (println "Retrieved basic information for" n
                     "names. Getting full details..."))
        all-info (doall
                  (map-indexed
                   (fn [idx {ns :ns, name :name, :as m}]
                     ;; Make each of ex, sa, and com always a vector,
                     ;; never nil. If examples returns non-nil, it
                     ;; includes both a vector of examples and a
                     ;; URL. We discard the URL here, since it is
                     ;; already available in all-names-urls.
                     (let [_ (when verbose
                               (printf "%d/%d " (inc idx) n)
                               (print (str ns "/" name) " examples:")
                               (flush))
                           ex (if-let [x (examples ns name)]
                                (:examples x)
                                [])
                           _ (when verbose
                               (print (count ex) " see-alsos:")
                               (flush))
                           sa (if-let [x (see-also ns name)] x [])
                           _ (when verbose
                               (print (count sa) " comments:")
                               (flush))
                           com (if-let [x (comments ns name)] x [])
                           _ (when verbose
                               (println (count com)))]
                       (assoc m :examples ex :see-alsos sa :comments com)))
                   all-names-urls))
        all-info-map (reduce (fn [big-map one-name-info]
                               (assoc big-map
                                 (str (:ns one-name-info) "/"
                                      (:name one-name-info))
                                 one-name-info))
                             {} all-info)
        all-info-map (use-sorted-maps all-info-map)
        now (str (java.util.Date.))]
    (with-open [f (io/writer fname)]
      (binding [*out* f]
        (pprint {:snapshot-time now,
                 :snapshot-info all-info-map})))
;;    (with-open [f (io/writer "debug-all-info-out.txt")]
;;      (binding [*out* f]
;;        (pprint {:snapshot-time now,
;;                 :snapshot-info all-info})))
    ))


(defn ^String ns-name-of-full-sym-name
  [^String s]
  (if-let [[_ ns-name] (re-find #"^([\D&&[^/]].*)/(/|[\D&&[^/]][^/]*)$" s)]
    ns-name))


(defn example-counts
  [sym-name-data-pairs]
  (map #(count (:examples (val %))) sym-name-data-pairs))


(defn print-snapshot-stats
  "Show a summary of all namespaces in the snapshot that have at least
  one example for one of its symbols. Below is an example of the
  output.

   #   # syms  % syms  avg / max
   of   with    with   examples
  syms examps examples per sym   Namespace
  ---- ------ -------- --------- -----------------------
  [ ... many lines deleted here ... ]
  596    442    74.2%    1.5  7 clojure.core
  4      1    25.0%    1.0  1 clojure.data
  13      1     7.7%    1.0  1 clojure.inspector
  [ ... many lines deleted here ... ]
  ---- ------ -------- --------- -----------------------
  1419    563    39.7%

  Printed stats for 51 namespaces (200 others with a total of 2155 symbols have
  no examples)"
  []
  (let [{:keys [data]} @*cd-client-mode*
        total-num-syms (count data)
        data-by-ns (group-by #(ns-name-of-full-sym-name (key %)) data)
        all-ns (set (keys data-by-ns))
        nss-with-at-least-1-example
        (->> (keys data-by-ns)
             (map (fn [ns]
                    [ns (example-counts (get data-by-ns ns))]))
             (filter (fn [[_ns ex-counts]]
                       (pos? (count (remove zero? ex-counts)))))
             (map first))
        nss-with-no-examples (set/difference
                              all-ns (set nss-with-at-least-1-example))]
    (printf " #   # syms  %% syms  avg / max\n")
    (printf " of   with    with   examples\n")
    (printf "syms examps examples per sym   Namespace\n")
    (printf "---- ------ -------- --------- -----------------------\n")
    (doseq [ns-name (sort nss-with-at-least-1-example)]
      (let [all-syms (get data-by-ns ns-name)
            ex-counts (example-counts all-syms)
            at-least-1-ex-counts (remove zero? ex-counts)]
        (when (pos? (count at-least-1-ex-counts))
          (printf "%4d %6d %8s %6s %2d %s"
                  (count all-syms)
                  (count at-least-1-ex-counts)
                  (let [n (count all-syms)]
                    (if (zero? n)
                      (format "%8s" "N/A")
                      (format "%7.1f%%"
                              (* 100.0 (/ (count at-least-1-ex-counts) n)))))
                  (let [n (count at-least-1-ex-counts)]
                    (if (zero? n)
                      (format "%6s" "N/A")
                      (format "%6.1f"
                              (double (/ (reduce + 0 at-least-1-ex-counts)
                                         n)))))
                  (apply max ex-counts)
                  ns-name)
          (printf "\n"))))
    (printf "---- ------ -------- --------- -----------------------\n")
    (let [num-syms-shown (reduce + 0
                                 (map #(count (get data-by-ns %))
                                      nss-with-at-least-1-example))
          num-at-least-1-ex (reduce + 0
                                    (map #(count
                                           (remove zero?
                                                   (example-counts
                                                    (get data-by-ns %))))
                                         nss-with-at-least-1-example))]
      (printf "%4d %6d %7.1f%%\n"
              num-syms-shown
              num-at-least-1-ex
              (* 100.0 (/ num-at-least-1-ex num-syms-shown)))
      (printf "\n")
      (printf (str "Printed stats for %d namespaces (%d others with a "
                   "total of %d symbols have no examples)\n")
              (count nss-with-at-least-1-example)
              (count nss-with-no-examples)
              (- total-num-syms num-syms-shown))
      (printf "\nList of namespaces that have no examples:\n")
      (doseq [ns-name (sort nss-with-no-examples)]
        (printf "%4d %s\n" (count (get data-by-ns ns-name)) ns-name)))))
