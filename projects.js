// Search and sort for the generated card grid in projects.html.
const grid = document.querySelector(".projects");
const cards = Array.from(grid.querySelectorAll(".card"));
const search = document.getElementById("project-search");
const sort = document.getElementById("project-sort");
const dirButton = document.getElementById("project-dir");
const count = document.getElementById("project-count");

const normalize = (s) => s.toLowerCase().replace(/\s+/g, " ").trim();
const text = new Map(cards.map((card) => [card, normalize(card.textContent)]));

const compare = {
  stars: (a, b) => a.dataset.stars - b.dataset.stars,
  name: (a, b) => a.dataset.name.localeCompare(b.dataset.name),
  author: (a, b) => a.dataset.author.localeCompare(b.dataset.author),
};

function render() {
  const key = sort.value;
  const sign = dirButton.dataset.dir === "asc" ? 1 : -1;
  const sorted = cards.slice().sort((a, b) => {
    // Projects without an original author always go last.
    if (key === "author" && !a.dataset.author !== !b.dataset.author) {
      return a.dataset.author ? -1 : 1;
    }
    return sign * compare[key](a, b) || compare.name(a, b);
  });

  const terms = normalize(search.value).split(" ").filter(Boolean);
  let shown = 0;
  for (const card of sorted) {
    const match = terms.every((t) => text.get(card).includes(t));
    card.hidden = !match;
    if (match) shown++;
    grid.appendChild(card);
  }
  count.textContent = shown === cards.length
    ? `${cards.length} projects`
    : `${shown} of ${cards.length} projects`;
}

function setDir(dir) {
  dirButton.dataset.dir = dir;
  const label = dir === "asc" ? "Ascending" : "Descending";
  dirButton.setAttribute("aria-label", label);
  dirButton.title = `${label} (click to reverse)`;
}

search.addEventListener("input", render);
// Most stars first is the useful default; names read best A–Z.
const defaultDir = () => (sort.value === "stars" ? "desc" : "asc");

sort.addEventListener("change", () => { setDir(defaultDir()); render(); });
dirButton.addEventListener("click", () => {
  setDir(dirButton.dataset.dir === "asc" ? "desc" : "asc");
  render();
});

setDir(defaultDir());
document.querySelector(".controls").hidden = false;
render();
