;;; Guix package definition for space-age
;;;
;;; Uses clojure-build-system
;;;
;;; For more details, see:
;;; - /run/current-system/profile/share/guile/site/3.0/guix/build/clojure-build-system.scm
;;; - /run/current-system/profile/share/guile/site/3.0/guix/build/ant-build-system.scm

(use-modules ((gnu packages clojure)      #:select (clojure))
             ((gnu packages compression)  #:select (zip))
             ((gnu packages java)         #:select (openjdk))
             ((guix build-system clojure) #:select (clojure-build-system))
             ((guix git-download)         #:select (git-fetch git-reference git-file-name))
             ((guix licenses)             #:select (epl2.0))
             ((guix packages)             #:select (package origin base32)))

;; NOTE: When compiling Java files in #:java-source-dirs and Clojure
;;       files in #:source-dirs, the classpath will be specified by
;;       the CLASSPATH environment variable, which is set during the
;;       configure phase to contain all JARs in our package inputs.

(package
 (name "space-age")
 (version "2023.03.17-5feb703")
 (home-page "https://gitlab.com/lambdatronic/space-age")
 (source (origin
          (method git-fetch)
          (uri (git-reference
                (url home-page)
                (commit "5feb703c724b078347249c728d6cc9ca94191272")))
          (file-name (git-file-name name version))
          (sha256
           (base32
            "0jcmp8gpzbd4cvsj3l5frd6m2mkrsc579yjx7ccrqsri0v1rgqnh"))))
 (build-system clojure-build-system)
 (arguments
  `(;; 0. The specified #:jdk and #:clojure packages will be used for
    ;;    compiling source code. The #:zip package will be used to
    ;;    repackage JARs when fixing their timestamps.
    #:jdk              ,openjdk
    #:clojure          ,clojure
    #:zip              ,zip
    ;; 1. *.java files in #:java-source-dirs are compiled with `javac`
    ;;    into #:java-compile-dir.
    #:java-source-dirs '()
    #:java-compile-dir "java-classes/"
    ;; 2. *.clj(c)? files in #:source-dirs are transformed into their
    ;;    namespaces and compiled with `(run! compile libs)`,
    ;;    including the namespaces in #:aot-include and excluding the
    ;;    namespaces in #:aot-exclude, into #:compile-dir.
    #:source-dirs      '("src/" "resources/")
    #:aot-include      '(#:all)
    #:aot-exclude      '(data-readers)
    #:compile-dir      "classes/"
    ;; 3. One JAR file is created for each entry in #:jar-names. These
    ;;    JARs contain all the class files in #:java-compile-dir and
    ;;    #:compile-dir (that originated directly from our source
    ;;    code). If #:omit-source? is #f, all the files in
    ;;    #:java-source-dirs and #:source-dirs will also be included
    ;;    in the JARs. If a #:main-class is specified (as a symbol
    ;;    naming a compiled Java class), then its main method will be
    ;;    set as the entrypoint for each JAR.
    #:jar-names        '("space-age-2023.03.17-5feb703.jar" "space-age.jar")
    #:omit-source?     #f
    #:main-class       'space_age.server
    ;; 4. If #:tests? is #t, *.clj(c)? files in #:test-dirs are
    ;;    transformed into their namespaces, including the namespaces
    ;;    in #:test-include and excluding the namespaces in
    ;;    #:test-exclude. Once per JAR in #:jar-names, a Clojure JVM
    ;;    is launched with the JAR and #:test-dirs on the classpath.
    ;;    All the test namespaces are required along with
    ;;    `clojure.test`, and then `clojure.test/run-tests` is called
    ;;    on all the test namespaces to determine if they pass or not.
    #:tests?           #t
    #:test-dirs        '("test/")
    #:test-include     '(#:all)
    #:test-exclude     '()
    ;; 5. All the JARs created thus far are copied to the default
    ;;    target directory (./share/java/).
    ;;
    ;; 6. All top-level files with base name matching #:doc-regex as
    ;;    well as all files (recursively) inside #:doc-dirs are copied
    ;;    to the default documentation directory
    ;;    (./share/doc/$NAME-VERSION/).
    #:doc-regex        "^(README.*|.*\\.html|.*\\.org|.*\\.md|\\.markdown|\\.txt)$"
    #:doc-dirs         '()))
 (synopsis "Space-Age is a Gemini server written in Clojure.")
 (description "Feature-complete implementation of Gemini protocol specification v0.14.3 (November 29th, 2020 - gemini://gemini.circumlunar.space/docs/specification.gmi). Provides a unique Ring-like programming model for server-side scripting.")
 (license epl2.0))
