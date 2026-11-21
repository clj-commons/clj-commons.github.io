(ns clj-commons.projects
  "Generates projects.html. Run with:

     GITHUB_TOKEN=$(gh auth token) clojure -X clj-commons.projects/generate!"
  (:require [tentacles.repos :as repos]
            [tentacles.users :as users]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import (org.jsoup Jsoup)))

(def opts {:oauth-token (System/getenv "GITHUB_TOKEN")})

(def ^:dynamic *retry-delays-ms*
  "Waits between attempts."
  [1000 2000 4000 8000])

(defn with-retries
  "Calls `f`, retrying with exponential backoff if it throws."
  [what f]
  (loop [[delay & delays] *retry-delays-ms*]
    (let [result (try {:value (f)}
                      (catch Exception e
                        (when-not delay
                          (throw (ex-info (str "Giving up on " what) {} e)))
                        (println "Retrying" what "in" delay "ms:" (ex-message e))))]
      (if result
        (:value result)
        (do (Thread/sleep (long delay))
            (recur delays))))))

(defn api-error [resp]
  (ex-info (str "GitHub API returned " (:status resp "an unexpected response"))
           {:response resp}))

(defn org-repos []
  (with-retries "listing clj-commons repos"
    #(let [repos (repos/org-repos "clj-commons" (assoc opts :all-pages true))]
       (if (and (sequential? repos) (every? :name repos))
         repos
         (throw (api-error repos))))))

(defn raw-file
  "Contents of `path` on the repo's default branch, or nil if it doesn't exist."
  [{:keys [name default_branch]} path]
  (let [url (str "https://raw.githubusercontent.com/clj-commons/" name "/" default_branch "/" path)]
    (with-retries url
      #(try (slurp url)
            (catch java.io.FileNotFoundException _ nil)))))

(defn usernames
  "GitHub usernames (without the @) mentioned in an ORIGINATOR or CODEOWNERS file."
  [s]
  (->> (str/split-lines (or s ""))
       (remove #(str/starts-with? % "#"))
       (mapcat #(re-seq #"(?<=@)[\w./-]+" %))
       (distinct)))

(defn user
  "The user's display name, falling back to their @username when GitHub has
  no name for them or the account no longer exists."
  [login]
  (let [u (with-retries (str "GitHub user " login)
            #(let [u (users/user login opts)]
               (cond (:login u) u
                     (= 404 (:status u)) nil
                     :else (throw (api-error u)))))]
    {:login login
     :name  (or (not-empty (:name u)) (str "@" login))}))

(defn with-people [repo]
  (assoc repo
         :originator (some-> (raw-file repo "ORIGINATOR") usernames first user)
         :code-owners (->> (or (raw-file repo ".github/CODEOWNERS")
                               (raw-file repo "CODEOWNERS"))
                           usernames
                           (mapv user))))

(defn user-link [{:keys [login name]}]
  ;; Non-breaking spaces keep each name on one line.
  [:a {:href (str "https://github.com/" login)} (str/replace name " " " ")])

(defn ->hiccup [{:keys [name html_url description stargazers_count originator code-owners]}]
  [:article.card {:data-name name
                  :data-stars stargazers_count
                  ;; Sort "@login" fallbacks by the login, not the "@".
                  :data-author (str/replace-first (:name originator "") #"^@" "")}
   [:header
    [:h2 [:a {:href html_url} name]]
    [:a.stars {:href (str html_url "/stargazers")
               :title (str stargazers_count " GitHub stars")}
     (format "★ %,d" stargazers_count)]]
   (when description [:p description])
   [:dl
    (when originator
      (list [:dt "Original author"] [:dd (user-link originator)]))
    (when (seq code-owners)
      (list [:dt "Maintainers"] [:dd (interpose ", " (map user-link code-owners))]))]])

(def own-repos? #{"clj-commons.github.io" "meta" "formatter" "infra" ".github"})

(defn make-project-list []
  (->> (org-repos)
       (remove :archived)
       (remove (comp own-repos? :name))
       (sort-by :stargazers_count >)
       (map (fn [repo] (println (:name repo)) repo)) ; progress
       (map with-people)
       (map ->hiccup)))

(def footer
  [:footer
   [:nav
    [:a {:href "/"} "Home"]
    [:a {:href "/projects.html"} "Projects"]
    [:a {:href "/accepting-projects.html"} "Accepting projects"]
    [:a {:href "/maintaining-projects.html"} "Maintaining projects"]
    [:a {:href "https://github.com/clj-commons"} "GitHub"]
    [:a {:href "http://clojurians.net"} "Clojurians Slack"]]])

(defn page [projects]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Projects · CLJ Commons"]
    [:link {:rel "stylesheet" :href "/style.css"}]
    [:link {:rel "icon" :href "/logo.svg" :type "image/svg+xml"}]
    [:script {:src "/projects.js" :defer true}]]
   [:body.wide
    [:div.logo
     [:a {:href "/" :title "CLJ Commons (Home)"}
      [:img {:alt "CLJ Commons Logo" :src "/logo.svg"}]]]
    [:h1 "Projects"]
    [:p "Here is a list of projects currently under the clj-commons umbrella. Some of the projects have dedicated maintainers, others are maintained by clj-commons. If you would want to take over maintainership of any of the projects maintained by @clj-commons, please drop us an issue either in the "
     [:a {:href "https://github.com/clj-commons/meta"} "meta"]
     " project or in the project itself."]
    ;; Hidden until projects.js runs, so it never shows without JavaScript.
    [:div.controls {:hidden true}
     [:select#project-sort {:aria-label "Sort by"}
      [:option {:value "stars"} "Stars"]
      [:option {:value "name"} "Name"]
      [:option {:value "author"} "Original author"]]
     [:button#project-dir {:type "button" :data-dir "desc" :aria-label "Descending"}
      [:svg {:viewBox "0 0 16 16" :width 16 :height 16 :aria-hidden "true"}
       [:path {:d "M8 2v12M3 9l5 5 5-5" :fill "none" :stroke "currentColor"
               :stroke-width 1.75 :stroke-linecap "round" :stroke-linejoin "round"}]]]
     [:span#project-count {:aria-live "polite"}]
     [:input#project-search {:type "search"
                             :placeholder "Search"
                             :aria-label "Search projects, authors and maintainers"}]]
    [:div.projects projects]
    footer]])

(defn pretty-print
  "Re-indents an HTML document so the committed file is readable and diffs well."
  [html]
  (let [doc (Jsoup/parse ^String html)]
    (-> doc .outputSettings (.indentAmount 2) (.outline false))
    (.outerHtml doc)))

(defn generate! [_]
  (spit "projects.html"
        (pretty-print (str "<!DOCTYPE html>" (h/html (page (make-project-list)))))))

(comment
  (generate! nil))
