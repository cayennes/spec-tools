(ns spec-tools.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec :as s]
            [clojure.string :as str]
            [spec-tools.core :as st]
            [spec-tools.spec :as spec]
            [spec-tools.type :as type]
            [spec-tools.form :as form]
            [spec-tools.conform :as conform]))

(s/def ::age (s/and spec/integer? #(> % 10)))
(s/def ::over-a-million (s/and spec/int? #(> % 1000000)))
(s/def ::lat spec/double?)
(s/def ::language (s/and spec/keyword? #{:clojure :clojurescript}))
(s/def ::truth spec/boolean?)
(s/def ::uuid spec/uuid?)
(s/def ::birthdate spec/inst?)

(deftest coerce-test
  (is (= spec/boolean? (st/coerce-spec ::truth)))
  (is (= spec/boolean? (st/coerce-spec spec/boolean?)))
  (is (thrown? #?(:clj Exception, :cljs js/Error) (st/coerce-spec ::INVALID))))

(deftest spec?-test
  (testing "spec"
    (let [spec (s/spec integer?)]
      (is (= spec (s/spec? spec)))
      (is (nil? (st/spec? spec)))))
  (testing "Spec"
    (let [spec (st/spec integer?)]
      (is (= spec (s/spec? spec)))
      (is (= spec (st/spec? spec))))))

(deftest spec-test
  (let [my-integer? (st/spec integer?)]

    (testing "creation"
      (testing "succeeds"
        (is (= spec/integer?
               (st/spec integer?)
               (st/spec {:spec integer?})
               (st/spec {:spec integer?, :type :long})
               (st/spec integer? {:type :long})
               (st/create-spec {:spec integer?})
               (st/create-spec {:spec integer? :type :long})
               (st/create-spec {:spec integer?, :form `integer?})
               (st/create-spec {:spec integer?, :form `integer?, :type :long}))))

      (testing "anonymous functions"

        (testing ":form default to ::s/unknown"
          (let [spec (st/create-spec
                       {:name "positive?"
                        :spec (fn [x] (pos? x))})]
            (is (st/spec? spec))
            (is (= (:form spec) ::s/unknown))))

        (testing ":form and :type can be provided"
          (let [spec (st/create-spec
                       {:name "positive?"
                        :spec (fn [x] (pos? x))
                        :type :long
                        :form `(fn [x] (pos? x))})]
            (is (st/spec? spec))))))

    (testing "wrapped predicate work as a predicate"
      (is (true? (my-integer? 1)))
      (is (false? (my-integer? "1"))))

    (testing "wrapped spec does not work as a predicate"
      (let [my-spec (st/spec (s/keys :req [::age]))]
        (is (thrown? #?(:clj Exception, :cljs js/Error) (my-spec {::age 20})))))

    (testing "adding info"
      (let [info {:description "desc"
                  :example 123}]
        (is (= info (:info (assoc my-integer? :info info))))))

    (testing "are specs"
      (is (true? (s/valid? my-integer? 1)))
      (is (false? (s/valid? my-integer? "1")))

      (testing "fully qualifed predicate symbol is returned with s/form"
        (is (= ['spec-tools.core/spec
                #?(:clj  'clojure.core/integer?
                   :cljs 'cljs.core/integer?)
                {:type :long}] (s/form my-integer?)))
        (is (= ['spec 'integer? {:type :long}] (s/describe my-integer?))))

      (testing "type resolution"
        (is (= (st/spec integer?)
               (st/spec integer? {:type :long}))))

      (testing "serialization"
        (let [spec (st/spec integer? {:description "cool", :type ::integer})]
          (is (= `(st/spec integer? {:description "cool", :type ::integer})
                 (s/form spec)
                 (st/deserialize (st/serialize spec))))))

      (testing "gen"
        (is (seq? (s/exercise my-integer?)))))))

(deftest doc-test

  (testing "creation"
    (is (= (st/doc integer? {:description "kikka"})
           (st/doc {:spec integer?, :description "kikka"})
           (st/doc integer? {:description "kikka"})
           (st/spec {:spec integer?, :description "kikka", :type nil}))))

  (testing "just docs, #12"
    (let [spec (st/doc integer? {:description "kikka"})]
      (is (= "kikka" (:description spec)))
      (is (true? (s/valid? spec 1)))
      (is (false? (s/valid? spec "1")))
      (is (= `(st/spec integer? {:description "kikka", :type nil})
             (st/deserialize (st/serialize spec))
             (s/form spec))))))

(deftest reason-test
  (let [expected-problem {:path [] :pred 'pos-int?, :val -1, :via [], :in []}]
    (testing "explain-data"
      (is (= #?(:clj  #:clojure.spec{:problems [expected-problem]}
                :cljs #:cljs.spec{:problems [expected-problem]})
             (st/explain-data (st/spec pos-int?) -1)
             (s/explain-data (st/spec pos-int?) -1))))
    (testing "explain-data with reason"
      (is (= #?(:clj  #:clojure.spec{:problems [(assoc expected-problem :reason "positive")]}
                :cljs #:cljs.spec{:problems [(assoc expected-problem :reason "positive")]})
             (st/explain-data (st/spec pos-int? {:reason "positive"}) -1)
             (s/explain-data (st/spec pos-int? {:reason "positive"}) -1))))))

(deftest spec-tools-conform-test
  (testing "in default mode"
    (testing "nothing is conformed"
      (is (= st/+invalid+ (st/conform ::age "12")))
      (is (= st/+invalid+ (st/conform ::over-a-million "1234567")))
      (is (= st/+invalid+ (st/conform ::lat "23.1234")))
      (is (= st/+invalid+ (st/conform ::language "clojure")))
      (is (= st/+invalid+ (st/conform ::truth "false")))
      (is (= st/+invalid+ (st/conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
      (is (= st/+invalid+ (st/conform ::birthdate "2014-02-18T18:25:37.456Z")))
      (is (= st/+invalid+ (st/conform ::birthdate "2014-02-18T18:25:37Z")))))

  (testing "string-conforming"
    (let [conform #(st/conform %1 %2 conform/string-conforming)]
      (testing "everything gets conformed"
        (is (= 12 (conform ::age "12")))
        (is (= 1234567 (conform ::over-a-million "1234567")))
        (is (= 23.1234 (conform ::lat "23.1234")))
        (is (= false (conform ::truth "false")))
        (is (= :clojure (conform ::language "clojure")))
        (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
               (conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
        (is (= #inst "2014-02-18T18:25:37.456Z"
               (conform ::birthdate "2014-02-18T18:25:37.456Z")))
        (is (= #inst "2014-02-18T18:25:37Z"
               (conform ::birthdate "2014-02-18T18:25:37Z"))))))

  (testing "json-conforming"
    (let [conform #(st/conform %1 %2 conform/json-conforming)]
      (testing "some are not conformed"
        (is (= st/+invalid+ (conform ::age "12")))
        (is (= st/+invalid+ (conform ::over-a-million "1234567")))
        (is (= st/+invalid+ (conform ::lat "23.1234")))
        (is (= st/+invalid+ (conform ::truth "false"))))
      (testing "some are conformed"
        (is (= :clojure (conform ::language "clojure")))
        (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
               (conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
        (is (= #inst "2014-02-18T18:25:37.456Z"
               (conform ::birthdate "2014-02-18T18:25:37.456Z")))
        (is (= #inst "2014-02-18T18:25:37Z"
               (conform ::birthdate "2014-02-18T18:25:37Z")))))))

(deftest conform!-test
  (testing "suceess"
    (is (= 12 (st/conform! ::age "12" conform/string-conforming))))
  (testing "failing"
    (is (thrown? #?(:clj Exception, :cljs js/Error) (st/conform! ::age "12")))
    (try
      (st/conform! ::age "12")
      (catch #?(:clj Exception, :cljs js/Error) e
        (let [data (ex-data e)]
          (is (= {:type ::st/conform
                  :problems [{:path [], :pred 'integer?, :val "12", :via [::age], :in []}]
                  :spec :spec-tools.core-test/age
                  :value "12"}
                 data)))))))

(deftest explain-tests
  (testing "without conforming"
    (is (= st/+invalid+ (st/conform spec/int? "12")))
    (is (= {::s/problems [{:path [], :pred 'int?, :val "12", :via [], :in []}]}
           (st/explain-data spec/int? "12")))
    (is (= "val: \"12\" fails predicate: int?\n"
           (with-out-str (st/explain spec/int? "12")))))
  (testing "with conforming"
    (is (= 12 (st/conform spec/int? "12" conform/string-conforming)))
    (is (= nil (st/explain-data spec/int? "12" conform/string-conforming)))
    (is (= "Success!\n"
           (with-out-str (st/explain spec/int? "12" conform/string-conforming))))))

(deftest conform-unform-explain-tests
  (testing "specs"
    (let [spec (st/spec (s/or :int spec/int? :bool spec/boolean?))
          value "1"]
      (is (= st/+invalid+ (st/conform spec value)))
      (is (= [:int 1] (st/conform spec value conform/string-conforming)))
      (is (= 1 (s/unform spec (st/conform spec value conform/string-conforming))))
      (is (= nil (st/explain-data spec value conform/string-conforming)))))
  (testing "regexs"
    (let [spec (st/spec (s/* (s/cat :key spec/keyword? :val spec/int?)))
          value [:a "1" :b "2"]]
      (is (= st/+invalid+ (st/conform spec value)))
      (is (= [{:key :a, :val 1} {:key :b, :val 2}] (st/conform spec value conform/string-conforming)))
      (is (= [:a 1 :b 2] (s/unform spec (st/conform spec value conform/string-conforming))))
      (is (= nil (st/explain-data spec value conform/string-conforming))))))

(s/def ::height integer?)
(s/def ::weight integer?)
(s/def ::person (st/spec (s/keys :req-un [::height ::weight])))

(deftest map-specs-test
  (let [person {:height 200, :weight 80, :age 36}]

    (testing "conform"
      (is (= {:height 200, :weight 80, :age 36}
             (s/conform ::person person)
             (st/conform ::person person))))

    (testing "stripping extra keys"
      (is (= {:height 200, :weight 80}
             (st/conform ::person person conform/strip-extra-keys-conforming))))

    (testing "failing on extra keys"
      (is (= st/+invalid+
             (st/conform ::person person conform/fail-on-extra-keys-conforming))))))

(s/def ::human (st/spec (s/keys :req-un [::height ::weight]) {:type ::human}))

(defn bmi [{:keys [height weight]}]
  (let [h (/ height 100)]
    (double (/ weight (* h h)))))

(deftest custom-map-specs-test
  (let [person {:height 200, :weight 80}
        bmi-conformer (fn [_ human]
                        (assoc human :bmi (bmi human)))]

    (testing "conform"
      (is (= {:height 200, :weight 80}
             (s/conform ::human person)
             (st/conform ::human person))))

    (testing "bmi-conforming"
      (is (= {:height 200, :weight 80, :bmi 20.0}
             (st/conform ::human person {::human bmi-conformer}))))))

(deftest unform-test
  (let [unform-conform #(s/unform %1 (st/conform %1 %2 conform/string-conforming))]
    (testing "conformed values can be unformed"
      (is (= 12 (unform-conform ::age "12")))
      (is (= 1234567 (unform-conform ::age "1234567")))
      (is (= 23.1234 (unform-conform ::lat "23.1234")))
      (is (= false (unform-conform ::truth "false")))
      (is (= :clojure (unform-conform ::language "clojure")))
      (is (= #uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e"
             (unform-conform ::uuid "07dbf30f-c99e-4e5d-b76e-5cbdac3b381e")))
      (is (= #inst "2014-02-18T18:25:37.456Z"
             (unform-conform ::birthdate "2014-02-18T18:25:37.456Z")))
      (is (= #inst "2014-02-18T18:25:37.456Z"
             (unform-conform ::birthdate "2014-02-18T18:25:37.456Z"))))))

(deftest extending-test
  (let [my-conforming (-> conform/string-conforming
                          (assoc
                            :keyword
                            (fn [_ value]
                              (-> value
                                  str/upper-case
                                  str/reverse
                                  keyword))))]
    (testing "string-conforming"
      (is (= :kikka (st/conform spec/keyword? "kikka" conform/string-conforming))))
    (testing "my-conforming"
      (is (= :AKKIK (st/conform spec/keyword? "kikka" my-conforming))))))

(deftest map-test
  (testing "nested map spec"
    (let [st-map (st/coll-spec
                   ::my-map
                   {::id integer?
                    ::age ::age
                    :boss spec/boolean?
                    (st/req :name) string?
                    (st/opt :description) string?
                    :languages #{keyword?}
                    :orders [{:id int?
                              :description string?}]
                    :address {:street string?
                              :zip string?}})
          s-keys (st/spec
                   (s/keys
                     :req [::id ::age]
                     :req-un [:spec-tools.core-test$my-map/boss
                              :spec-tools.core-test$my-map/name
                              :spec-tools.core-test$my-map/languages
                              :spec-tools.core-test$my-map/orders
                              :spec-tools.core-test$my-map/address]
                     :opt-un [:spec-tools.core-test$my-map/description]))]

      (testing "normal keys-spec-spec is generated"
        (is (= (s/form s-keys) (s/form st-map))))
      (testing "nested keys are in the registry"
        (let [generated-keys (->> (st/registry #"spec-tools.core-test\$my-map.*") (map first) set)]
          (is (= #{:spec-tools.core-test$my-map/boss
                   :spec-tools.core-test$my-map/name
                   :spec-tools.core-test$my-map/description
                   :spec-tools.core-test$my-map/languages
                   :spec-tools.core-test$my-map/orders
                   :spec-tools.core-test$my-map$orders/id
                   :spec-tools.core-test$my-map$orders/description
                   :spec-tools.core-test$my-map/address
                   :spec-tools.core-test$my-map$address/zip
                   :spec-tools.core-test$my-map$address/street}
                 generated-keys))))
      (testing "validating"
        (let [value {::id 1
                     ::age 63
                     :boss true
                     :name "Liisa"
                     :languages #{:clj :cljs}
                     :orders [{:id 1, :description "cola"}
                              {:id 2, :description "kebab"}]
                     :description "Liisa is a valid boss"
                     :address {:street "Amurinkatu 2"
                               :zip "33210"}}
              bloated (-> value
                          (assoc-in [:KIKKA] true)
                          (assoc-in [:address :KIKKA] true))]

          (testing "data can be validated"
            (is (true? (s/valid? st-map value))))

          (testing "map-conforming works recursively"
            (is (= value
                   (st/conform st-map bloated {:map conform/strip-extra-keys}))))))))

  (testing "top-level vector"
    (is (true?
          (s/valid?
            (st/coll-spec ::vector [{:olipa {:kerran string?}}])
            [{:olipa {:kerran "avaruus"}}
             {:olipa {:kerran "elämä"}}])))
    (is (false?
          (s/valid?
            (st/coll-spec ::vector [{:olipa {:kerran string?}}])
            [{:olipa {:kerran :muumuu}}]))))

  (testing "top-level set"
    (is (true?
          (s/valid?
            (st/coll-spec ::vector #{{:olipa {:kerran string?}}})
            #{{:olipa {:kerran "avaruus"}}
              {:olipa {:kerran "elämä"}}})))
    (is (false?
          (s/valid?
            (st/coll-spec ::vector #{{:olipa {:kerran string?}}})
            #{{:olipa {:kerran :muumuu}}}))))

  (testing "mega-nested"
    (is (true?
          (s/valid?
            (st/coll-spec ::vector [[[[[[[[[[string?]]]]]]]]]])
            [[[[[[[[[["kikka" "kakka" "kukka"]]]]]]]]]])))
    (is (false?
          (s/valid?
            (st/coll-spec ::vector [[[[[[[[[[string?]]]]]]]]]])
            [[[[[[[[[123]]]]]]]]]))))

  (testing "predicate keys"
    (is
      (true?
        (s/valid?
          (st/coll-spec ::pred-keys {string? {keyword? [integer?]}})
          {"winning numbers" {:are [1 12 46 45]}
           "empty?" {:is []}})))
    (is
      (false?
        (s/valid?
          (st/coll-spec ::pred-keys {string? {keyword? [integer?]}})
          {"invalid spec" "is this"})))))

(deftest extract-extra-info-test
  (testing "all keys types are extracted"
    (is (= {:keys #{::age :lat ::truth :uuid}}
           (st/extract-extra-info
             (s/form (s/keys
                       :req [::age]
                       :req-un [::lat]
                       :opt [::truth]
                       :opt-un [::uuid]))))))

  (testing "ands and ors are flattened"
    (is (= {:keys #{::age ::lat ::uuid}}
           (st/extract-extra-info
             (s/form (s/keys
                       :req [(or ::age (and ::uuid ::lat))])))))))

(deftest type-inference-test
  (testing "for core predicates"
    (is (= :long (type/resolve-type `integer?))))
  (testing "::s/unknown for unknowns"
    (is (= nil (type/resolve-type #(> % 2)))))
  (testing "types"
    (is (not (empty? (type/types))))
    (is (contains? (type/types) :boolean)))
  (testing "type-symbols"
    (is (not (empty? (type/type-symbols))))
    (is (contains? (type/type-symbols) 'clojure.spec/keys))
    (is (contains? (type/type-symbols) 'clojure.core/integer?))))

(deftest form-inference-test
  (testing "for core predicates"
    (is (= `integer? (form/resolve-form integer?))))
  (testing "::s/unknown for unknowns"
    (is (= ::s/unknown (form/resolve-form #(> % 2))))))

(deftest spec-inference-test
  (testing "for core predicates"
    (is (= spec/integer? (spec/resolve-spec integer?))))
  (testing "nil for unknowns"
    (is (= nil (spec/resolve-spec #(> % 2))))))
