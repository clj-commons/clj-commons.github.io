(ns clj-commons.projects
  (:require [tentacles.repos :as repos]
            [tentacles.users :as users]
            [clojure.string :as str]
            [hiccup.core :as hiccup]))

(def opts {:auth "slipset:redacted"})


(defn original-author [{:keys [name] :as repo}]
  (users/user (str/replace (subs (slurp (str "https://raw.githubusercontent.com/clj-commons/" name "/master/ORIGINATOR")) 1) "\n" "") opts))

(defn code-owners [{:keys [name] :as repo}]
  (let [owners (try (slurp (str "https://raw.githubusercontent.com/clj-commons/" name "/master/.github/CODEOWNERS"))
                    (catch Exception _
                      (slurp (str "https://raw.githubusercontent.com/clj-commons/" name "/master/CODEOWNERS"))))]
    (->> owners
         (str/split-lines)
         (remove #(str/starts-with? % "#"))
         (mapcat (fn [l]
                   (->> (str/split l #"\s+")
                        (drop 1)
                        (map #(subs % 1)))))
         (reduce (fn add-user [users u]
                   (conj users (users/user u opts))) []))))

(defn with-original-author [repo]
  (assoc repo :originator (original-author repo)))

(defn with-code-owners [repo]
  (assoc repo :code-owners (code-owners repo)))

(defn ->hiccup [{:keys [name description stargazers_count open_issues originator code-owners] :as repo}]
  [:span
   [:h2 [:a {:href (:html_url repo)} name]]
   [:p description]
   [:ul
    [:li "Original author: " [:a {:href (:html_url originator)} (:name originator)]]
    [:li "Maintainers: " (interpose ", " (map (fn [c] [:a {:href (:html_url c)} (:name c) ]) code-owners))]]])

(def own-repos? #{"clj-commons.github.io" "meta" "formatter" "infra" ".github"})

(defn print-repo [repo] (println repo) repo)

(defn make-project-list []
  (->> (repos/org-repos "clj-commons" opts)
       (sort-by :stargazers_count)
       (reverse)
       (map print-repo) ; to get a sense of progress when running this!
       (remove (comp own-repos? :name))
       (map with-original-author)
       (map with-code-owners)
       (map ->hiccup)))

(def style "<style type=\"text/css\">
        body {
            margin: 40px auto;
            max-width: 650px;
            line-height: 1.6;
            font-size: 18px;
            color: #444;
            padding: 0 10px
        }

        h1,
        h2,
        h3 {
            line-height: 1.2
        }
    </style>")

(def preamble
  [:p "Here is a list of projects currently under the clj-commons umbrella. Some of the projects have dedicated maintainers, others are maintained by clj-commons. If you would want to take over maintainership of any of the projects maintained by @clj-commons, please drop us an issue either in the <a href = \"https://github.com/clj-commons/meta\">meta</a> project or in the project itself."])

(comment
  (spit "projects.html" (hiccup/html [:head style [:body preamble (make-project-list)]]))
  )
