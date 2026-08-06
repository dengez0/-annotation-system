let canvas = document.getElementById('editor');
let ctx = canvas.getContext('2d');
const PROJECT_KEY = encodeURIComponent(MAIN_FOLDER) + '/' + encodeURIComponent(SUBFOLDER);
let images = []; // Array of {name, processed}
let currentImageIndex = 0;
let currentImage = new Image();
let shapes = []; // {label, points: [[x1,y1], [x2,y2]]}
let isDrawing = false;
let startPoint = null;
let currentMouse = null;
let selectedShapeIndex = -1;
let isRectMode = true; // Default to Rect Mode as per previous state, can toggle with R
let isKeepPrevAnnotation = false;
let autoReuseLabel = true; // Auto-use last label for next shape (X-AnyLabeling style)
let projectLabels = new Set(); // Stores all unique labels
let pendingShape = null; // Temporary shape before labeling
let activeLabel = null; // Pre-selected label for drawing (X-AnyLabeling style)
let selectedFiles = new Set(); // Set of indices of selected files for batch delete

// High-contrast distinct color palette (dark-background optimized)
const DISTINCT_COLORS = [
    '#FF4444', '#44FF44', '#4488FF', '#FFDD44', '#FF44FF',
    '#44FFFF', '#FF8844', '#88FF44', '#FF4488', '#44FF88',
    '#8844FF', '#FFFF44', '#FF6644', '#44FFCC', '#CC44FF',
    '#FFCC44', '#44CCFF', '#FF4466', '#66FF44', '#FF44CC',
    '#44FF66', '#FFAA44', '#4466FF', '#AAFF44', '#FF44AA',
    '#44FFAA', '#FF7744', '#7744FF', '#77FF44', '#FF4477',
];

// Deterministic color from label name
function getLabelColor(label) {
    let hash = 0;
    for (let i = 0; i < label.length; i++) {
        hash = ((hash << 5) - hash) + label.charCodeAt(i);
        hash |= 0;
    }
    return DISTINCT_COLORS[Math.abs(hash) % DISTINCT_COLORS.length];
}

// Dragging variables
let isDragging = false;
let draggingShapeIndex = -1;
let dragStartMouse = null;
let dragOriginalPoints = null;

// Resizing variables
let isResizing = false;
let resizeHandle = null; // 'tl', 't', 'tr', 'r', 'br', 'b', 'bl', 'l'
// let isFourPointResize = false; // Deprecated: Always use 4 points
const HANDLE_SIZE = 8;

// Zoom/Pan Variables
let viewScale = 1;
let viewPan = { x: 0, y: 0 };
let isPanning = false;
let panStart = null;

// Loading Version Control
let currentLoadId = 0;

// Task Control
let currentTaskId = null;
let taskPollInterval = null;
let shouldScrollToActive = false;

// Context Menu
let contextMenuShapeIndex = -1;

// 初始化
async function init() {
    try {
        console.log("Starting optimized init (v2)...");
        document.getElementById('current-filename').innerText = "Loading project...";

        // Wire file filter
        const filterInput = document.getElementById('file-filter');
        if (filterInput) {
            filterInput.addEventListener('input', filterFiles);
        }

        // 1. Fetch Images (Critical Path)
        const imgRes = await fetch('/api/images/' + PROJECT_KEY);
        if (!imgRes.ok) throw new Error("Failed to load images");
        images = await imgRes.json();
        console.log("Images loaded:", images.length);

        // Update toolbar count
        const countEl = document.getElementById('toolbar-count');
        if (countEl && images.length > 0) {
            countEl.textContent = '1/' + images.length;
        }

        // Render immediately if we have images
        if (images && images.length > 0) {
            renderFileList();
            loadImage(0);
        } else {
            document.getElementById('current-filename').innerText = "No images found";
        }

        // 2. Fetch Labels (Background - Non-blocking)
        fetch('/api/labels/' + PROJECT_KEY)
            .then(r => r.json())
            .then(labels => {
                labels.forEach(l => projectLabels.add(l));
                console.log("Labels loaded (background):", labels.length);
                renderClassList();
            })
            .catch(e => console.warn("Background label load warning:", e));

    } catch (e) {
        console.error("Initialization error:", e);
        document.getElementById('current-filename').innerText = "Error loading project: " + (e.message || e);
    }
    updateModeUI();
    updateTransform();
}

function renderFileList() {
    const container = document.getElementById('file-list-container');
    container.innerHTML = '';
    images.forEach((imgObj, idx) => {
        const div = document.createElement('div');
        div.className = 'file-item' + (idx === currentImageIndex ? ' active' : '');
        div.id = 'file-item-' + idx;

        // Interactive checkbox for multi-select
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.className = 'file-checkbox';
        checkbox.checked = selectedFiles.has(idx);
        checkbox.title = 'Select for batch delete';
        checkbox.onclick = (e) => {
            e.stopPropagation();
            toggleFileSelection(idx);
        };

        // Processed status indicator (green dot)
        const status = document.createElement('span');
        status.className = 'file-status' + (imgObj.processed ? ' processed' : '');
        status.title = imgObj.processed ? 'Has annotation' : 'No annotation';

        const nameSpan = document.createElement('span');
        nameSpan.innerText = imgObj.name;
        nameSpan.className = 'file-name';

        div.appendChild(checkbox);
        div.appendChild(status);
        div.appendChild(nameSpan);

        // Click on file item: if holding Ctrl/Cmd, toggle selection; else load image
        div.onclick = (e) => {
            if (e.ctrlKey || e.metaKey) {
                toggleFileSelection(idx);
            } else {
                loadImage(idx);
            }
        };
        container.appendChild(div);
    });

    // Auto scroll to active item
    scrollToActive();
    updateFileCount();
    updateDeleteButton();
}

function updateFileListActive() {
    // Update classes without full re-render
    const items = document.querySelectorAll('.file-item');
    items.forEach((item, idx) => {
        if (idx === currentImageIndex) item.classList.add('active');
        else item.classList.remove('active');
    });
    scrollToActive();
}

function scrollToActive() {
    const active = document.getElementById('file-item-' + currentImageIndex);
    if (active) {
        active.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
}

function toggleKeepPrev(checked) {
    isKeepPrevAnnotation = checked;
}

function toggleAutoReuseLabel(checked) {
    autoReuseLabel = checked;
    if (!checked) {
        // When turning off, clear current active label
        activeLabel = null;
        renderClassList();
        updateModeUI();
    }
}

async function loadImage(index) {
    if (index < 0 || index >= images.length) return;
    
    currentImageIndex = index;
    // Update Load ID to invalidate previous pending loads
    const thisLoadId = ++currentLoadId;
    
    const imgObj = images[index];
    document.getElementById('current-filename').innerText = (index + 1) + '/' + images.length + ' - ' + imgObj.name;
    updateFileListActive(); // Use optimized update

    // Update toolbar count
    const countEl = document.getElementById('toolbar-count');
    if (countEl) countEl.textContent = (index + 1) + '/' + images.length;

    // Update status bar
    const statusFn = document.getElementById('status-filename');
    if (statusFn) statusFn.textContent = imgObj.name;

    currentImage.src = '/data/' + PROJECT_KEY + '/' + encodeURIComponent(imgObj.name);
    currentImage.onload = () => {
        if (thisLoadId !== currentLoadId) return; // Ignore if obsolete

        canvas.width = currentImage.width;
        canvas.height = currentImage.height;

        fitImageToScreen(); // Auto fit and center
        updateResolutionDisplay();

        loadAnnotation(imgObj.name, thisLoadId);
    };
    currentImage.onerror = () => {
        if (thisLoadId !== currentLoadId) return;
        console.error('Failed to load image (404 or network error): ' + imgObj.name);
        // Clear canvas and show error text
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.fillStyle = '#666';
        ctx.font = '16px "Microsoft YaHei", sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('⚠ 图片加载失败: ' + imgObj.name, canvas.width / 2, canvas.height / 2);
        ctx.textAlign = 'start';
        updateResolutionDisplay();
        // Don't block UI — user can still navigate away
    };
}

async function loadAnnotation(filename, loadId) {
    // Clear shapes initially
    shapes = [];
    selectedShapeIndex = -1;
    
    const jsonName = filename.substring(0, filename.lastIndexOf('.')) + '.json';
    
    try {
        const res = await fetch('/data/' + PROJECT_KEY + '/' + jsonName);
        if (loadId !== currentLoadId) return; // Check again after await
        
        if (res.ok) {
            const data = await res.json();
            if (data.shapes) {
                shapes = data.shapes.map(s => ({
                    label: s.label,
                    points: s.points
                }));
            }
        }
    } catch (e) {
        console.log('No annotation found');
    }
    
    if (loadId === currentLoadId) {
        draw();
    }
}

function getHandleRects(shape) {
    const pts = shape.points;
    let x, y, w, h;
    if (pts.length === 2) {
        x = pts[0][0]; y = pts[0][1];
        w = pts[1][0] - x; h = pts[1][1] - y;
    } else {
        const xs = pts.map(p => p[0]);
        const ys = pts.map(p => p[1]);
        x = Math.min(...xs); y = Math.min(...ys);
        w = Math.max(...xs) - x; h = Math.max(...ys) - y;
    }

    const s = HANDLE_SIZE / viewScale;
    const hw = s / 2;

    return [
        { type: 'tl', x: x - hw, y: y - hw, w: s, h: s },
        { type: 't',  x: x + w/2 - hw, y: y - hw, w: s, h: s },
        { type: 'tr', x: x + w - hw, y: y - hw, w: s, h: s },
        { type: 'r',  x: x + w - hw, y: y + h/2 - hw, w: s, h: s },
        { type: 'br', x: x + w - hw, y: y + h - hw, w: s, h: s },
        { type: 'b',  x: x + w/2 - hw, y: y + h - hw, w: s, h: s },
        { type: 'bl', x: x - hw, y: y + h - hw, w: s, h: s },
        { type: 'l',  x: x - hw, y: y + h/2 - hw, w: s, h: s }
    ];
}

function drawHandles(shape) {
    const handles = getHandleRects(shape);
    ctx.fillStyle = 'white';
    ctx.strokeStyle = 'black';
    ctx.lineWidth = 1 / viewScale;

    handles.forEach(h => {
        ctx.fillRect(h.x, h.y, h.w, h.h);
        ctx.strokeRect(h.x, h.y, h.w, h.h);
    });
}

function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(currentImage, 0, 0);

    // 绘制所有框
    shapes.forEach((shape, idx) => {
        const pts = shape.points;
        let x, y, w, h;
        if (pts.length === 2) {
            x = pts[0][0]; y = pts[0][1];
            w = pts[1][0] - x; h = pts[1][1] - y;
        } else { // 4 points (兼容旧数据)
            const xs = pts.map(p => p[0]);
            const ys = pts.map(p => p[1]);
            x = Math.min(...xs); y = Math.min(...ys);
            w = Math.max(...xs) - x; h = Math.max(...ys) - y;
        }
        
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        const color = getLabelColor(shape.label);
        if (idx === selectedShapeIndex) {
            ctx.strokeStyle = '#00ff00';
            ctx.lineWidth = 3 / viewScale;
        } else {
            ctx.strokeStyle = color;
            ctx.lineWidth = 2 / viewScale;
        }
        if (ctx.lineWidth < 1) ctx.lineWidth = 1; // Minimum width
        ctx.stroke();

        if (idx === selectedShapeIndex) {
            drawHandles(shape);
        }
    });

    if (isDrawing && startPoint && currentMouse) {
        ctx.strokeStyle = '#00ff00';
        ctx.lineWidth = 2 / viewScale;
        ctx.strokeRect(startPoint.x, startPoint.y, currentMouse.x - startPoint.x, currentMouse.y - startPoint.y);
    }
    
    renderShapeList();
}

function renderClassList() {
    const list = document.getElementById('class-list');
    if (!list) return;
    list.innerHTML = '';

    const sortedLabels = Array.from(projectLabels).sort();

    sortedLabels.forEach(label => {
        const div = document.createElement('div');
        div.className = 'label-chip';
        if (label === activeLabel) div.classList.add('active');
        div.title = label + (label === activeLabel ? ' (active)' : '');

        const swatch = document.createElement('span');
        swatch.className = 'label-color-swatch';
        swatch.style.backgroundColor = getLabelColor(label);

        const text = document.createElement('span');
        text.innerText = label;

        div.appendChild(swatch);
        div.appendChild(text);

        div.onclick = () => {
            if (activeLabel === label) {
                // Deselect
                activeLabel = null;
            } else {
                activeLabel = label;
            }
            renderClassList();
            updateModeUI();
        };

        list.appendChild(div);
    });
}

function renderShapeList() {
    const list = document.getElementById('shape-list');
    list.innerHTML = '';
    let activeDiv = null;
    shapes.forEach((s, idx) => {
        const div = document.createElement('div');
        div.className = 'shape-item' + (idx === selectedShapeIndex ? ' active' : '');

        const swatch = document.createElement('span');
        swatch.className = 'shape-color-swatch';
        swatch.style.backgroundColor = getLabelColor(s.label);

        const labelSpan = document.createElement('span');
        labelSpan.innerText = s.label;

        const delBtn = document.createElement('span');
        delBtn.className = 'shape-delete';
        delBtn.innerHTML = '&times;';
        delBtn.title = 'Delete';
        delBtn.onclick = (e) => {
            e.stopPropagation();
            deleteShape(idx);
        };

        div.appendChild(swatch);
        div.appendChild(labelSpan);
        div.appendChild(delBtn);

        // Use timeout to distinguish single vs double click
        let clickTimer = null;
        div.onclick = () => {
            if (clickTimer) {
                clearTimeout(clickTimer);
                clickTimer = null;
                // Double click detected
                selectedShapeIndex = idx;
                contextMenuShapeIndex = idx;
                draw();
                editShapeLabel();
            } else {
                clickTimer = setTimeout(() => {
                    clickTimer = null;
                    // Single click
                    selectedShapeIndex = idx;
                    shouldScrollToActive = true;
                    draw();
                }, 200);
            }
        };
        div.oncontextmenu = (e) => {
            e.preventDefault();
            if (clickTimer) { clearTimeout(clickTimer); clickTimer = null; }
            selectedShapeIndex = idx;
            shouldScrollToActive = true;
            draw();
            contextMenuShapeIndex = idx;
            showContextMenu(e.clientX, e.clientY);
        };
        list.appendChild(div);
        if (idx === selectedShapeIndex) {
            activeDiv = div;
        }
    });

    if (shouldScrollToActive && activeDiv) {
        activeDiv.scrollIntoView({ behavior: 'smooth', block: 'center' });
        shouldScrollToActive = false;
    }
}

function deleteShape(index) {
    shapes.splice(index, 1);
    selectedShapeIndex = -1;
    draw();
    saveCurrent(); // Auto save
}

// --- Zoom & Pan Logic ---

function updateTransform() {
    canvas.style.transformOrigin = '0 0';
    canvas.style.transform = `translate(${viewPan.x}px, ${viewPan.y}px) scale(${viewScale})`;
}

function updateZoomDisplay() {
    const pct = Math.round(viewScale * 100);
    const zoomPercent = document.getElementById('zoom-percent');
    const statusZoom = document.getElementById('status-zoom');
    if (zoomPercent) zoomPercent.textContent = pct + '%';
    if (statusZoom) statusZoom.textContent = 'Zoom: ' + pct + '%';
}

function updateResolutionDisplay() {
    const res = document.getElementById('status-resolution');
    if (res && currentImage.width) {
        res.textContent = currentImage.width + ' x ' + currentImage.height;
    }
}

function updateStatusBar() {
    const imgObj = images[currentImageIndex];
    if (imgObj) {
        const fn = document.getElementById('status-filename');
        if (fn) fn.textContent = imgObj.name;
    }
    updateResolutionDisplay();
    updateZoomDisplay();
}

function updateFileCount() {
    const visibleItems = document.querySelectorAll('#file-list-container .file-item');
    let visibleCount = 0;
    visibleItems.forEach(item => {
        if (item.style.display !== 'none') visibleCount++;
    });
    const el = document.getElementById('file-count');
    if (el) el.textContent = visibleCount + '/' + images.length;
}

function filterFiles() {
    const filterText = document.getElementById('file-filter').value.toLowerCase();
    const items = document.querySelectorAll('#file-list-container .file-item');
    images.forEach((img, idx) => {
        const item = document.getElementById('file-item-' + idx);
        if (item) {
            item.style.display = img.name.toLowerCase().includes(filterText) ? '' : 'none';
        }
    });
    updateFileCount();
}

// ── Batch file selection & delete ──

function toggleFileSelection(idx) {
    if (selectedFiles.has(idx)) {
        selectedFiles.delete(idx);
    } else {
        selectedFiles.add(idx);
    }
    // Update checkbox in DOM if it exists
    const item = document.getElementById('file-item-' + idx);
    if (item) {
        const cb = item.querySelector('.file-checkbox');
        if (cb) cb.checked = selectedFiles.has(idx);
    }
    updateDeleteButton();
}

function selectAllFiles() {
    const filterText = document.getElementById('file-filter').value.toLowerCase();
    images.forEach((img, idx) => {
        if (img.name.toLowerCase().includes(filterText)) {
            selectedFiles.add(idx);
        }
    });
    // Refresh checkboxes
    images.forEach((img, idx) => {
        const item = document.getElementById('file-item-' + idx);
        if (item) {
            const cb = item.querySelector('.file-checkbox');
            if (cb) cb.checked = selectedFiles.has(idx);
        }
    });
    updateDeleteButton();
}

function deselectAllFiles() {
    selectedFiles.clear();
    document.querySelectorAll('#file-list-container .file-checkbox').forEach(cb => {
        cb.checked = false;
    });
    updateDeleteButton();
}

function updateDeleteButton() {
    const count = selectedFiles.size;
    const delBtn = document.getElementById('delete-files-btn');
    if (delBtn) {
        delBtn.textContent = count > 0 ? '🗑 Delete (' + count + ')' : '🗑 Delete';
        delBtn.style.opacity = count > 0 ? '1' : '0.5';
    }
    const moveBtn = document.getElementById('move-files-btn');
    if (moveBtn) {
        moveBtn.textContent = count > 0 ? '📁 Move (' + count + ')' : '📁 Move';
        moveBtn.style.opacity = count > 0 ? '1' : '0.5';
    }
    const pasteBtn = document.getElementById('paste-files-btn');
    if (pasteBtn) {
        pasteBtn.textContent = count > 0 ? '📋 Paste (' + count + ')' : '📋 Paste';
        pasteBtn.style.opacity = count > 0 ? '1' : '0.5';
    }
}

// ── Paste selected files ──

async function pasteSelectedFiles() {
    if (selectedFiles.size === 0) {
        alert('请先在左侧文件列表勾选要复制的图片');
        return;
    }

    const count = selectedFiles.size;
    if (!confirm('确定要复制 ' + count + ' 张选中的图片及其JSON文件到 paste image 文件夹吗？')) return;

    const filenames = [];
    selectedFiles.forEach(idx => {
        if (idx < images.length) filenames.push(images[idx].name);
    });
    if (filenames.length === 0) return;

    const pasteBtn = document.getElementById('paste-files-btn');
    if (pasteBtn) { pasteBtn.disabled = true; pasteBtn.textContent = 'Copying...'; }

    try {
        const res = await fetch('/api/copy_files/' + PROJECT_KEY, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ filenames: filenames })
        });
        const result = await res.json();

        if (result.status === 'success') {
            alert('成功复制 ' + result.copied + ' 个文件到 paste image 文件夹');
        } else {
            alert('复制失败: ' + (result.error || 'Unknown error'));
        }
    } catch (e) {
        alert('请求失败: ' + e.message);
    }

    if (pasteBtn) { pasteBtn.disabled = false; updateDeleteButton(); }
}

async function deleteSelectedFiles() {
    if (selectedFiles.size === 0) {
        alert('请先在左侧文件列表勾选要删除的图片');
        return;
    }

    const count = selectedFiles.size;
    if (!confirm('确定要删除 ' + count + ' 张选中的图片及其对应的JSON标注文件吗？\n\n此操作不可撤销！')) {
        return;
    }

    const filenames = [];
    selectedFiles.forEach(idx => {
        if (idx < images.length) {
            filenames.push(images[idx].name);
        }
    });

    try {
        const res = await fetch('/api/delete_files/' + PROJECT_KEY, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ filenames: filenames })
        });
        const result = await res.json();

        if (result.status === 'success') {
            // Save current image name before refreshing
            const currentName = images[currentImageIndex] ? images[currentImageIndex].name : null;

            // Clear selection
            selectedFiles.clear();

            // Re-fetch image list instead of full page reload
            const imgRes = await fetch('/api/images/' + PROJECT_KEY);
            if (!imgRes.ok) throw new Error('Failed to reload images');
            images = await imgRes.json();

            // Try to stay on the same image, or fall back to nearby
            let targetIdx = -1;
            if (currentName) {
                // Find current image in new list (may have been deleted)
                targetIdx = images.findIndex(img => img.name === currentName);
            }
            if (targetIdx < 0) {
                // Current image was deleted — pick next or last
                targetIdx = Math.min(currentImageIndex, images.length - 1);
            }
            if (targetIdx < 0) targetIdx = 0;

            currentImageIndex = targetIdx;

            // Re-render file list with correct active item
            renderFileList();

            if (images.length > 0) {
                loadImage(currentImageIndex);
            } else {
                document.getElementById('current-filename').innerText = 'No images';
                const ctx = canvas.getContext('2d');
                ctx.clearRect(0, 0, canvas.width, canvas.height);
            }

            // Refresh labels
            fetch('/api/labels/' + PROJECT_KEY)
                .then(r => r.json())
                .then(labels => {
                    projectLabels.clear();
                    labels.forEach(l => projectLabels.add(l));
                    renderClassList();
                });

            alert('成功删除 ' + result.deleted + ' 个文件');
        } else {
            alert('删除失败: ' + (result.error || 'Unknown error'));
        }
    } catch (e) {
        alert('删除请求失败: ' + e.message);
    }
}

// ── Move selected files ──

let moveSelectedSubfolder = '';

function openMoveFilesModal() {
    if (selectedFiles.size === 0) {
        alert('请先在左侧文件列表勾选要移动的图片');
        return;
    }

    const modal = document.getElementById('move-files-modal');
    const countEl = document.getElementById('move-file-count');
    const listDiv = document.getElementById('move-subfolder-list');
    const newInput = document.getElementById('move-new-subfolder');
    const selectedDisplay = document.getElementById('move-selected-display');
    const resultDiv = document.getElementById('move-result');
    const executeBtn = document.getElementById('move-execute-btn');

    if (!modal) return;

    moveSelectedSubfolder = '';
    countEl.textContent = selectedFiles.size;
    newInput.value = '';
    selectedDisplay.style.display = 'none';
    selectedDisplay.textContent = '';
    resultDiv.style.display = 'none';
    resultDiv.textContent = '';
    executeBtn.disabled = true;
    listDiv.innerHTML = '<div style="padding:12px;text-align:center;color:var(--text-dim);">Loading...</div>';

    modal.style.display = 'block';

    // Fetch subfolders under "moved image"
    fetch('/api/subfolders/' + encodeURIComponent('moved image'))
        .then(r => r.json())
        .then(subfolders => {
            listDiv.innerHTML = '';
            if (subfolders.length === 0) {
                listDiv.innerHTML = '<div style="padding:12px;text-align:center;color:var(--text-dim);">No subfolders found. Create a new one below.</div>';
                return;
            }
            subfolders.forEach(sf => {
                const item = document.createElement('div');
                item.style.cssText = 'display:flex;align-items:center;padding:8px 12px;cursor:pointer;border-bottom:1px solid rgba(128,128,128,0.08);transition:background 0.15s;';
                item.innerHTML = '<span style="font-size:14px;margin-right:8px;">📁</span><span style="flex:1;font-size:13px;">' + sf.name + '</span><span style="font-size:11px;color:var(--text-dim);">' + sf.count + ' files</span>';
                item.onmouseenter = () => { if (!item.classList.contains('move-selected')) item.style.background = 'var(--bg-hover)'; };
                item.onmouseleave = () => { if (!item.classList.contains('move-selected')) item.style.background = ''; };
                item.onclick = () => {
                    document.querySelectorAll('#move-subfolder-list > div').forEach(d => { d.classList.remove('move-selected'); d.style.background = ''; });
                    item.classList.add('move-selected');
                    item.style.background = 'rgba(34,197,94,0.12)';
                    moveSelectedSubfolder = sf.name;
                    newInput.value = '';
                    selectedDisplay.style.display = 'block';
                    selectedDisplay.textContent = '→ moved image / ' + sf.name;
                    executeBtn.disabled = false;
                };
                listDiv.appendChild(item);
            });
        })
        .catch(() => {
            listDiv.innerHTML = '<div style="padding:12px;text-align:center;color:var(--accent-red);">Failed to load subfolders</div>';
        });

    // New subfolder input: typing enables the move button
    newInput.oninput = () => {
        const val = newInput.value.trim();
        if (val) {
            moveSelectedSubfolder = val;
            document.querySelectorAll('#move-subfolder-list > div').forEach(d => { d.classList.remove('move-selected'); d.style.background = ''; });
            selectedDisplay.style.display = 'block';
            selectedDisplay.textContent = '→ moved image / ' + val + ' (new)';
            executeBtn.disabled = false;
        } else if (!moveSelectedSubfolder || moveSelectedSubfolder === val) {
            // If field is cleared, revert to list selection or disable
            const selected = document.querySelector('#move-subfolder-list > div.move-selected');
            if (selected) {
                moveSelectedSubfolder = selected.querySelector('span:nth-child(2)').textContent;
                selectedDisplay.textContent = '→ moved image / ' + moveSelectedSubfolder;
            } else {
                moveSelectedSubfolder = '';
                selectedDisplay.style.display = 'none';
                executeBtn.disabled = true;
            }
        }
    };
}

function closeMoveFilesModal() {
    const modal = document.getElementById('move-files-modal');
    if (modal) modal.style.display = 'none';
}

async function executeMoveFiles() {
    if (!moveSelectedSubfolder) {
        alert('Please select or enter a destination subfolder.');
        return;
    }

    const resultDiv = document.getElementById('move-result');
    const executeBtn = document.getElementById('move-execute-btn');

    const filenames = [];
    selectedFiles.forEach(idx => {
        if (idx < images.length) {
            filenames.push(images[idx].name);
        }
    });

    if (filenames.length === 0) return;

    executeBtn.disabled = true;
    executeBtn.textContent = 'Moving...';
    resultDiv.style.display = 'block';
    resultDiv.style.color = 'var(--text-secondary)';
    resultDiv.textContent = 'Moving ' + filenames.length + ' file(s)...';

    try {
        const res = await fetch('/api/move_files/' + PROJECT_KEY, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                filenames: filenames,
                dest_main_folder: 'moved image',
                dest_subfolder: moveSelectedSubfolder
            })
        });
        const result = await res.json();

        if (result.status === 'success') {
            resultDiv.style.color = 'var(--accent-green)';
            resultDiv.textContent = 'Successfully moved ' + result.moved + ' file(s) to moved image/' + moveSelectedSubfolder;

            if (result.errors && result.errors.length > 0) {
                resultDiv.textContent += ' Errors: ' + result.errors.join(', ');
            }

            const currentName = images[currentImageIndex] ? images[currentImageIndex].name : null;
            selectedFiles.clear();

            const imgRes = await fetch('/api/images/' + PROJECT_KEY);
            if (!imgRes.ok) throw new Error('Failed to reload images');
            images = await imgRes.json();

            let targetIdx = -1;
            if (currentName) {
                targetIdx = images.findIndex(img => img.name === currentName);
            }
            if (targetIdx < 0) targetIdx = Math.min(currentImageIndex, images.length - 1);
            if (targetIdx < 0) targetIdx = 0;

            currentImageIndex = targetIdx;
            renderFileList();

            if (images.length > 0) {
                loadImage(currentImageIndex);
            } else {
                document.getElementById('current-filename').innerText = 'No images';
                ctx.clearRect(0, 0, canvas.width, canvas.height);
            }

            fetch('/api/labels/' + PROJECT_KEY)
                .then(r => r.json())
                .then(labels => {
                    projectLabels.clear();
                    labels.forEach(l => projectLabels.add(l));
                    renderClassList();
                });

            // Close after delay
            setTimeout(() => closeMoveFilesModal(), 1500);
        } else {
            resultDiv.style.color = 'var(--accent-red)';
            resultDiv.textContent = 'Error: ' + (result.error || 'Unknown error');
            executeBtn.disabled = false;
            executeBtn.textContent = 'Move';
        }
    } catch (e) {
        resultDiv.style.color = 'var(--accent-red)';
        resultDiv.textContent = 'Request failed: ' + e.message;
        executeBtn.disabled = false;
        executeBtn.textContent = 'Move';
    }
}

function zoomIn() {
    const container = document.getElementById('canvas-wrapper');
    const cw = container.clientWidth;
    const ch = container.clientHeight;
    const newScale = viewScale * 1.2;
    if (newScale > 50) return;
    viewPan.x = cw/2 - (cw/2 - viewPan.x) * 1.2;
    viewPan.y = ch/2 - (ch/2 - viewPan.y) * 1.2;
    viewScale = newScale;
    updateTransform();
    updateZoomDisplay();
    draw();
}

function zoomOut() {
    const container = document.getElementById('canvas-wrapper');
    const cw = container.clientWidth;
    const ch = container.clientHeight;
    const newScale = viewScale / 1.2;
    if (newScale < 0.05) return;
    viewPan.x = cw/2 - (cw/2 - viewPan.x) / 1.2;
    viewPan.y = ch/2 - (ch/2 - viewPan.y) / 1.2;
    viewScale = newScale;
    updateTransform();
    updateZoomDisplay();
    draw();
}

function zoomReset() {
    fitImageToScreen();
}

function setRectMode() {
    isRectMode = true;
    updateModeUI();
}

function setPointerMode() {
    isRectMode = false;
    updateModeUI();
}

function fitImageToScreen() {
    const container = document.getElementById('canvas-wrapper');
    const cw = container.clientWidth;
    const ch = container.clientHeight;
    const iw = canvas.width;
    const ih = canvas.height;

    if (iw === 0 || ih === 0) return;

    const scaleW = cw / iw;
    const scaleH = ch / ih;
    const scale = Math.min(scaleW, scaleH) * 0.95;

    viewScale = scale;
    viewPan.x = (cw - iw * scale) / 2;
    viewPan.y = (ch - ih * scale) / 2;

    updateTransform();
    updateZoomDisplay();
}

// Wheel Zoom
canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    
    // Mouse position relative to viewport
    const mx = e.clientX;
    const my = e.clientY;
    
    // Canvas container offset
    const containerRect = canvas.parentElement.getBoundingClientRect();
    const cx = mx - containerRect.left; // Mouse relative to container
    const cy = my - containerRect.top;
    
    const zoomIntensity = 0.1;
    const delta = e.deltaY > 0 ? (1 - zoomIntensity) : (1 + zoomIntensity);
    const newScale = viewScale * delta;
    
    if (newScale < 0.05) return; // Min zoom
    if (newScale > 50) return; // Max zoom
    
    viewPan.x = cx - (cx - viewPan.x) * (newScale / viewScale);
    viewPan.y = cy - (cy - viewPan.y) * (newScale / viewScale);
    viewScale = newScale;
    
    updateTransform();
    updateZoomDisplay();
    draw(); // Redraw to update line widths/font sizes
}, { passive: false });

// Double Click: edit label on shape, otherwise fit to screen
canvas.addEventListener('dblclick', (e) => {
    const pos = getMousePosInImage(e);
    // Hit-test shapes (prioritize smaller boxes)
    let found = -1;
    let minArea = Infinity;
    for (let i = shapes.length - 1; i >= 0; i--) {
        const s = shapes[i];
        const p = s.points;
        let bx, by, bw, bh;
        if (p.length === 2) {
            bx = Math.min(p[0][0], p[1][0]);
            by = Math.min(p[0][1], p[1][1]);
            bw = Math.abs(p[1][0] - p[0][0]);
            bh = Math.abs(p[1][1] - p[0][1]);
        } else {
            const xs = p.map(pt => pt[0]);
            const ys = p.map(pt => pt[1]);
            bx = Math.min(...xs); by = Math.min(...ys);
            bw = Math.max(...xs) - bx; bh = Math.max(...ys) - by;
        }
        if (pos.x >= bx && pos.x <= bx + bw && pos.y >= by && pos.y <= by + bh) {
            const area = bw * bh;
            if (area < minArea) {
                minArea = area;
                found = i;
            }
        }
    }
    if (found !== -1) {
        selectedShapeIndex = found;
        contextMenuShapeIndex = found;
        draw();
        editShapeLabel();
    } else {
        fitImageToScreen();
    }
});

// Panning (Right Click or Space+Left)
canvas.addEventListener('contextmenu', e => e.preventDefault()); // Disable context menu

// --- Mouse Events Wrapper ---

function getMousePosInImage(e) {
    const rect = canvas.getBoundingClientRect();
    // rect is the VISUAL bounding box of the canvas (transformed)
    
    // Standard mapping:
    const scaleX = canvas.width / rect.width;
    const scaleY = canvas.height / rect.height;
    
    return {
        x: (e.clientX - rect.left) * scaleX,
        y: (e.clientY - rect.top) * scaleY
    };
}

canvas.onmousedown = (e) => {
    // Right Click: check if on a shape first, otherwise pan
    if (e.button === 2) {
        const pos = getMousePosInImage(e);
        // Hit-test shapes (same logic as left-click)
        let found = -1;
        let minArea = Infinity;
        for (let i = shapes.length - 1; i >= 0; i--) {
            const s = shapes[i];
            const p = s.points;
            let bx, by, bw, bh;
            if (p.length === 2) {
                bx = Math.min(p[0][0], p[1][0]);
                by = Math.min(p[0][1], p[1][1]);
                bw = Math.abs(p[1][0] - p[0][0]);
                bh = Math.abs(p[1][1] - p[0][1]);
            } else {
                const xs = p.map(pt => pt[0]);
                const ys = p.map(pt => pt[1]);
                bx = Math.min(...xs); by = Math.min(...ys);
                bw = Math.max(...xs) - bx; bh = Math.max(...ys) - by;
            }
            if (pos.x >= bx && pos.x <= bx + bw && pos.y >= by && pos.y <= by + bh) {
                const area = bw * bh;
                if (area < minArea) {
                    minArea = area;
                    found = i;
                }
            }
        }
        if (found !== -1) {
            selectedShapeIndex = found;
            contextMenuShapeIndex = found;
            draw();
            showContextMenu(e.clientX, e.clientY);
            return;
        }
        startPan(e);
        return;
    }

    // Middle Click -> Pan
    if (e.button === 1) {
        startPan(e);
        return;
    }
    
    const pos = getMousePosInImage(e);
    const x = pos.x;
    const y = pos.y;

    // 1. Check Resize Handles (if a shape is selected)
    if (selectedShapeIndex !== -1) {
        const shape = shapes[selectedShapeIndex];
        const handles = getHandleRects(shape);
        for (let h of handles) {
             if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                 isResizing = true;
                 resizeHandle = h.type;
                 // Keep track of original rect for calculations
                 let sx, sy, sw, sh;
                 const pts = shape.points;
                 // Remove 2-point logic: Always treat as bounding box for resize calc
                 // isFourPointResize = (pts.length > 2);
                 console.log('Resize start. Enforcing 4-point output.');

                 if (pts.length === 2) {
                     sx = pts[0][0]; sy = pts[0][1];
                     sw = pts[1][0] - sx; sh = pts[1][1] - sy;
                 } else {
                     const xs = pts.map(p => p[0]);
                     const ys = pts.map(p => p[1]);
                     sx = Math.min(...xs); sy = Math.min(...ys);
                     sw = Math.max(...xs) - sx; sh = Math.max(...ys) - sy;
                 }
                 dragOriginalPoints = [[sx, sy], [sx+sw, sy+sh]];
                 return; // Stop processing other clicks
             }
        }
    }

    // Check hit test first
    let found = -1;
    let minArea = Infinity;

    for (let i = shapes.length - 1; i >= 0; i--) {
        const s = shapes[i];
        const p = s.points;
        let bx, by, bw, bh;
        if (p.length === 2) {
            bx = Math.min(p[0][0], p[1][0]);
            by = Math.min(p[0][1], p[1][1]);
            bw = Math.abs(p[1][0] - p[0][0]);
            bh = Math.abs(p[1][1] - p[0][1]);
        } else {
             const xs = p.map(pt => pt[0]);
             const ys = p.map(pt => pt[1]);
             bx = Math.min(...xs); by = Math.min(...ys);
             bw = Math.max(...xs) - bx; bh = Math.max(...ys) - by;
        }
        if (x >= bx && x <= bx + bw && y >= by && y <= by + bh) {
            const area = bw * bh;
            // 优先选择面积更小的框 (Prioritize smaller boxes)
            if (area < minArea) {
                minArea = area;
                found = i;
            }
        }
    }
    
    // Left Click Logic
    if (e.button === 0) {
        if (!isRectMode) {
            // Pointer Mode
            if (found !== -1) {
                // Drag Shape
                selectedShapeIndex = found;
                shouldScrollToActive = true;
                isDragging = true;
                draggingShapeIndex = found;
                dragStartMouse = {x, y};
                dragOriginalPoints = JSON.parse(JSON.stringify(shapes[found].points));
                draw();
            } else {
                // Clicked Empty Space in Pointer Mode -> Pan Image
                startPan(e);
            }
        } else {
            // Rect Mode -> Draw
            isDrawing = true;
            startPoint = {x, y};
            selectedShapeIndex = -1;
            draw();
        }
    }
};

function startPan(e) {
    isPanning = true;
    panStart = { x: e.clientX, y: e.clientY };
    canvas.style.cursor = 'grabbing';
}

canvas.onmousemove = (e) => {
    if (isPanning) {
        const dx = e.clientX - panStart.x;
        const dy = e.clientY - panStart.y;
        viewPan.x += dx;
        viewPan.y += dy;
        panStart = { x: e.clientX, y: e.clientY };
        updateTransform();
        return;
    }

    const pos = getMousePosInImage(e);
    currentMouse = pos;
    const x = pos.x;
    const y = pos.y;

    // Update cursor style
    let cursorSet = false;
    if (selectedShapeIndex !== -1 && !isResizing && !isDragging) {
         const handles = getHandleRects(shapes[selectedShapeIndex]);
         for (let h of handles) {
             if (x >= h.x && x <= h.x + h.w && y >= h.y && y <= h.y + h.h) {
                 const cursors = {
                     'tl': 'nw-resize', 't': 'n-resize', 'tr': 'ne-resize',
                     'r': 'e-resize', 'br': 'se-resize', 'b': 's-resize',
                     'bl': 'sw-resize', 'l': 'w-resize'
                 };
                 canvas.style.cursor = cursors[h.type];
                 cursorSet = true;
                 break;
             }
         }
    }

    if (!cursorSet) {
        if (!isRectMode && !isDragging && !isResizing) {
            let hover = false;
            for (let i = shapes.length - 1; i >= 0; i--) {
                const s = shapes[i];
                const p = s.points;
                let bx, by, bw, bh;
                if (p.length === 2) {
                    bx = Math.min(p[0][0], p[1][0]);
                    by = Math.min(p[0][1], p[1][1]);
                    bw = Math.abs(p[1][0] - p[0][0]);
                    bh = Math.abs(p[1][1] - p[0][1]);
                } else {
                    const xs = p.map(pt => pt[0]);
                    const ys = p.map(pt => pt[1]);
                    bx = Math.min(...xs); by = Math.min(...ys);
                    bw = Math.max(...xs) - bx; bh = Math.max(...ys) - by;
                }
                if (x >= bx && x <= bx + bw && y >= by && y <= by + bh) {
                    hover = true;
                    break;
                }
            }
            canvas.style.cursor = hover ? 'move' : 'grab'; // 'grab' indicates pannable
        } else if (isRectMode) {
            canvas.style.cursor = 'crosshair';
        }
    }

    if (isResizing && selectedShapeIndex !== -1) {
        let [p1, p2] = dragOriginalPoints;
        let [x1, y1] = p1;
        let [x2, y2] = p2;

        const imgW = currentImage.width;
        const imgH = currentImage.height;

        if (resizeHandle.includes('l')) x1 = Math.min(currentMouse.x, x2 - 5);
        if (resizeHandle.includes('r')) x2 = Math.max(currentMouse.x, x1 + 5);
        if (resizeHandle.includes('t')) y1 = Math.min(currentMouse.y, y2 - 5);
        if (resizeHandle.includes('b')) y2 = Math.max(currentMouse.y, y1 + 5);

        // Clamp to image boundaries
        x1 = Math.max(0, Math.min(imgW, x1));
        y1 = Math.max(0, Math.min(imgH, y1));
        x2 = Math.max(0, Math.min(imgW, x2));
        y2 = Math.max(0, Math.min(imgH, y2));

        // Force 4-point coordinates (TL, TR, BR, BL)
        shapes[selectedShapeIndex].points = [
            [x1, y1],
            [x2, y1],
            [x2, y2],
            [x1, y2]
        ];

        draw();
        return;
    }

    if (isDragging && draggingShapeIndex !== -1) {
        const dx = currentMouse.x - dragStartMouse.x;
        const dy = currentMouse.y - dragStartMouse.y;

        const imgW = currentImage.width;
        const imgH = currentImage.height;

        const newPoints = dragOriginalPoints.map(pt => {
            let nx = pt[0] + dx;
            let ny = pt[1] + dy;
            // Clamp to image boundaries
            nx = Math.max(0, Math.min(imgW, nx));
            ny = Math.max(0, Math.min(imgH, ny));
            return [nx, ny];
        });
        shapes[draggingShapeIndex].points = newPoints;
        draw();
        return;
    }

    if (isDrawing) draw();
};

canvas.onmouseup = (e) => {
    if (isPanning) {
        isPanning = false;
        canvas.style.cursor = isRectMode ? 'crosshair' : 'default';
        return;
    }

    if (isResizing) {
        isResizing = false;
        resizeHandle = null;
        dragOriginalPoints = null;
        saveCurrent();
        return;
    }

    if (isDragging) {
        isDragging = false;
        draggingShapeIndex = -1;
        dragStartMouse = null;
        dragOriginalPoints = null;
        saveCurrent();
        return;
    }

    if (!isDrawing) return;
    isDrawing = false;

    // Prevent tiny boxes (accidental clicks)
    if (Math.abs(currentMouse.x - startPoint.x) < 5 || Math.abs(currentMouse.y - startPoint.y) < 5) {
        draw();
        return;
    }

    // Build 4-point rectangle
    const x1 = Math.min(startPoint.x, currentMouse.x);
    const y1 = Math.min(startPoint.y, currentMouse.y);
    const x2 = Math.max(startPoint.x, currentMouse.x);
    const y2 = Math.max(startPoint.y, currentMouse.y);

    const newShape = {
        label: null,
        points: [
            [x1, y1],
            [x2, y1],
            [x2, y2],
            [x1, y2]
        ],
        shape_type: "rectangle"
    };

    // X-AnyLabeling style: auto-assign if label pre-selected
    if (activeLabel) {
        newShape.label = activeLabel;
        shapes.push(newShape);
        selectedShapeIndex = shapes.length - 1;
        draw();
        saveCurrent();
        // Auto-switch to Edit mode after drawing
        isRectMode = false;
        updateModeUI();
        // Clear active label if auto-reuse is off
        if (!autoReuseLabel) {
            activeLabel = null;
            renderClassList();
            updateModeUI();
        }
    } else {
        // No pre-selected label → show label modal
        pendingShape = newShape;
        openModal();
    }
};

// Modal Logic
function openModal() {
    const modal = document.getElementById('label-modal');
    const input = document.getElementById('new-label-input');
    const list = document.getElementById('modal-label-list');
    
    modal.style.display = 'block';
    input.value = '';
    input.focus();
    
    // Render recent labels
    list.innerHTML = '';
    projectLabels.forEach(label => {
        const div = document.createElement('div');
        div.className = 'label-suggestion';
        div.innerText = label;
        div.onclick = () => {
            input.value = label;
            confirmLabel();
        };
        list.appendChild(div);
    });
}

function closeModal() {
    document.getElementById('label-modal').style.display = 'none';
    pendingShape = null;
    draw(); // clear drawing
}

function confirmLabel() {
    const input = document.getElementById('new-label-input');
    const label = input.value.trim();
    if (!label) return;

    pendingShape.label = label;
    shapes.push(pendingShape);
    selectedShapeIndex = shapes.length - 1;

    // Add to project labels
    if (!projectLabels.has(label)) {
        projectLabels.add(label);
        renderClassList();
    }
    // Auto-set as active label for next drawing (if auto-reuse enabled)
    if (autoReuseLabel) {
        activeLabel = label;
    }
    renderClassList();

    closeModal();
    draw();
    saveCurrent();
    // Auto-switch to Edit mode
    isRectMode = false;
    updateModeUI();
}

// --- Context Menu Functions ---

function showContextMenu(x, y) {
    const menu = document.getElementById('context-menu');
    if (!menu) return;
    menu.style.display = 'block';
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';

    // Adjust if menu goes off-screen
    const rect = menu.getBoundingClientRect();
    if (rect.right > window.innerWidth) {
        menu.style.left = (x - rect.width) + 'px';
    }
    if (rect.bottom > window.innerHeight) {
        menu.style.top = (y - rect.height) + 'px';
    }
}

function hideContextMenu() {
    const menu = document.getElementById('context-menu');
    if (menu) menu.style.display = 'none';
    contextMenuShapeIndex = -1;
}

function editShapeLabel() {
    if (contextMenuShapeIndex < 0 || contextMenuShapeIndex >= shapes.length) return;
    const shapeIdx = contextMenuShapeIndex;
    hideContextMenu();

    const shape = shapes[shapeIdx];
    const modal = document.getElementById('edit-label-modal');
    const input = document.getElementById('edit-label-input');
    const palette = document.getElementById('edit-label-palette');

    if (!modal || !input || !palette) return;

    modal.style.display = 'block';
    input.value = shape.label;
    input.focus();
    input.select();

    // Render label palette with color swatches
    palette.innerHTML = '';
    const sortedLabels = Array.from(projectLabels).sort();
    let selectedChip = null;

    sortedLabels.forEach(label => {
        const color = getLabelColor(label);
        const chip = document.createElement('div');
        chip.className = 'edit-label-chip';
        if (label === shape.label) {
            chip.classList.add('selected');
            selectedChip = chip;
        }

        const swatch = document.createElement('span');
        swatch.className = 'edit-label-chip-swatch';
        swatch.style.backgroundColor = color;

        const text = document.createElement('span');
        text.innerText = label;

        chip.appendChild(swatch);
        chip.appendChild(text);

        chip.onclick = () => {
            if (selectedChip) selectedChip.classList.remove('selected');
            chip.classList.add('selected');
            selectedChip = chip;
            input.value = label;
            // Apply immediately for palette click
            shapes[shapeIdx].label = label;
            if (!projectLabels.has(label)) {
                projectLabels.add(label);
                renderClassList();
            }
            closeEditLabelModal();
            draw();
            saveCurrent();
        };

        palette.appendChild(chip);
    });

    // Scroll selected chip into view
    if (selectedChip) {
        selectedChip.scrollIntoView({ block: 'nearest' });
    }

    // Update chip selection when typing
    input.oninput = () => {
        const typed = input.value.trim().toLowerCase();
        const chips = palette.querySelectorAll('.edit-label-chip');
        let matched = null;
        chips.forEach(chip => {
            const chipLabel = chip.querySelector('span:last-child').innerText.toLowerCase();
            if (chipLabel === typed) {
                chip.classList.add('selected');
                matched = chip;
            } else {
                chip.classList.remove('selected');
            }
        });
        selectedChip = matched;
    };
}

function closeEditLabelModal() {
    document.getElementById('edit-label-modal').style.display = 'none';
    contextMenuShapeIndex = -1;
}

function confirmEditLabel() {
    const input = document.getElementById('edit-label-input');
    const newLabel = input.value.trim();
    if (!newLabel) return;

    // Find selected shape (can't rely on contextMenuShapeIndex after hideContextMenu)
    if (selectedShapeIndex >= 0 && selectedShapeIndex < shapes.length) {
        shapes[selectedShapeIndex].label = newLabel;

        if (!projectLabels.has(newLabel)) {
            projectLabels.add(newLabel);
            renderClassList();
        }
    }

    closeEditLabelModal();
    draw();
    saveCurrent();
}

function deleteContextShape() {
    if (contextMenuShapeIndex >= 0 && contextMenuShapeIndex < shapes.length) {
        deleteShape(contextMenuShapeIndex);
    }
    hideContextMenu();
}

// Close context menu on click outside
document.addEventListener('click', (e) => {
    const menu = document.getElementById('context-menu');
    if (menu && menu.style.display === 'block') {
        if (!menu.contains(e.target)) {
            hideContextMenu();
        }
    }
});

// Close context menu on Escape
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        const menu = document.getElementById('context-menu');
        if (menu && menu.style.display === 'block') {
            hideContextMenu();
        }
    }
});

function updateModeUI() {
    const status = document.getElementById('mode-status');
    const drawBtn = document.getElementById('draw-rect-btn');
    const pointerBtn = document.getElementById('pointer-btn');

    let labelInfo = activeLabel ? ' | Label: ' + activeLabel : ' | No label';

    if (isRectMode) {
        if (status) { status.innerText = 'Draw' + labelInfo; status.style.color = '#4ade80'; }
        canvas.style.cursor = 'crosshair';
        if (drawBtn) drawBtn.classList.add('active');
        if (pointerBtn) pointerBtn.classList.remove('active');
    } else {
        if (status) { status.innerText = 'Edit' + labelInfo; status.style.color = '#60a5fa'; }
        canvas.style.cursor = 'default';
        if (drawBtn) drawBtn.classList.remove('active');
        if (pointerBtn) pointerBtn.classList.add('active');
    }
}

async function saveCurrent() {
    const imgObj = images[currentImageIndex];
    const filename = imgObj.name;
    
    const json = {
        version: "5.2.1",
        flags: {},
        shapes: shapes.map(s => ({
            label: s.label,
            points: s.points,
            group_id: null,
            description: "",
            shape_type: "rectangle",
            flags: {}
        })),
        imagePath: filename,
        imageData: null,
        imageHeight: currentImage.height,
        imageWidth: currentImage.width
    };

    const res = await fetch('/api/save/' + PROJECT_KEY, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            filename: filename,
            json: json
        })
    });
    
    const result = await res.json();
    if(result.status === 'success') {
        imgObj.processed = true; // Mark as processed
        // We do NOT call renderFileList() here to avoid resetting scroll or zoom
        // Just update the green dot for the current index
        const item = document.getElementById('file-item-' + currentImageIndex);
        if (item) {
            const status = item.querySelector('.file-status');
            if (status) {
                status.classList.add('processed');
                status.title = 'Has annotation';
            }
        }
    }
}

async function createEmptyJsons() {
    const res = await fetch('/api/create_empty_jsons/' + PROJECT_KEY, { method: 'POST' });
    const data = await res.json();
    if (data.status === 'success') {
        alert(`Created: ${data.created}\nAlready had JSON: ${data.skipped}\nErrors: ${data.errors}`);
        // Refresh the image list to show updated checkmarks
        init();
    } else {
        alert('Error: ' + (data.error || 'Unknown error'));
    }
}

// === YOLO Export ===

async function openExportYoloModal() {
    const modal = document.getElementById('export-yolo-modal');
    if (!modal) return;
    modal.style.display = 'flex';

    const container = document.getElementById('yolo-label-checkboxes');
    if (!container) return;
    container.innerHTML = '<div style="padding:10px;color:var(--text-dim);text-align:center;width:100%;">Loading...</div>';

    // Fetch all labels used in this project
    try {
        const res = await fetch('/api/labels/' + PROJECT_KEY);
        const labels = await res.json();
        container.innerHTML = '';
        if (labels.length === 0) {
            container.innerHTML = '<div style="padding:10px;color:var(--text-dim);text-align:center;width:100%;">No labels found in project</div>';
            return;
        }
        for (const label of labels) {
            const labelEl = document.createElement('label');
            labelEl.style.cssText = 'display:flex;align-items:center;gap:4px;font-size:12px;color:var(--text-primary);cursor:pointer;padding:3px 8px;border:1px solid var(--border-color);border-radius:3px;white-space:nowrap;';
            labelEl.innerHTML = `<input type="checkbox" class="yolo-label-cb" value="${label}" checked> ${label}`;
            container.appendChild(labelEl);
        }
        document.getElementById('yolo-select-all').checked = true;
    } catch (e) {
        container.innerHTML = '<div style="color:red;padding:10px;">Error loading labels</div>';
    }
}

function closeExportYoloModal() {
    const modal = document.getElementById('export-yolo-modal');
    if (modal) modal.style.display = 'none';
}

function toggleSelectAllYolo(checked) {
    document.querySelectorAll('.yolo-label-cb').forEach(cb => cb.checked = checked);
}

async function runExportYolo() {
    const checkboxes = document.querySelectorAll('.yolo-label-cb:checked');
    const selectedLabels = Array.from(checkboxes).map(cb => cb.value);
    if (selectedLabels.length === 0) {
        alert('Please select at least one label.');
        return;
    }

    try {
        const res = await fetch('/api/export_yolo/' + PROJECT_KEY, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ labels: selectedLabels })
        });
        const data = await res.json();
        if (data.status === 'success') {
            alert(`Export complete!\n\nExported: ${data.exported} files\nSkipped (no JSON): ${data.skipped}\nErrors: ${data.errors}\nClasses: ${data.class_count}\n\nOutput: data/${MAIN_FOLDER}/${SUBFOLDER}/labels/`);
            closeExportYoloModal();
        } else {
            alert('Error: ' + (data.error || 'Unknown error'));
        }
    } catch (e) {
        alert('Export failed: ' + e.message);
    }
}

// === End YOLO Export ===

async function nextImage() {
    if (currentImageIndex < images.length - 1) {
        // Keep Previous Logic
        if (isKeepPrevAnnotation) {
            // Copy current shapes to next
            const currentShapes = JSON.parse(JSON.stringify(shapes)); // Deep copy
            
            // Move to next
            currentImageIndex++;
            // Increment Load ID for the new image
            const thisLoadId = ++currentLoadId;
            
            const nextImgObj = images[currentImageIndex];

            document.getElementById('current-filename').innerText = (currentImageIndex + 1) + '/' + images.length + ' - ' + nextImgObj.name;
            updateFileListActive();
            // Update toolbar count
            const countEl = document.getElementById('toolbar-count');
            if (countEl) countEl.textContent = (currentImageIndex + 1) + '/' + images.length;
            // Update status bar
            const statusFn = document.getElementById('status-filename');
            if (statusFn) statusFn.textContent = nextImgObj.name;
            // Do NOT reset zoom here if we want continuous flow? 
            // Usually next image implies new context, so reset fit is safer.
            // fitImageToScreen() will be called in onload
            
            currentImage.src = '/data/' + PROJECT_KEY + '/' + encodeURIComponent(nextImgObj.name);
            currentImage.onload = () => {
                if (thisLoadId !== currentLoadId) return;

                canvas.width = currentImage.width;
                canvas.height = currentImage.height;
                fitImageToScreen();

                // Apply shapes
                shapes = currentShapes;
                draw();
                saveCurrent(); // Save immediately
            };
            currentImage.onerror = () => {
                if (thisLoadId !== currentLoadId) return;
                console.error('Failed to load image (404 or network error): ' + nextImgObj.name);
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                ctx.fillStyle = '#666';
                ctx.font = '16px "Microsoft YaHei", sans-serif';
                ctx.textAlign = 'center';
                ctx.fillText('⚠ 图片加载失败: ' + nextImgObj.name, canvas.width / 2, canvas.height / 2);
                ctx.textAlign = 'start';
            };
        } else {
            loadImage(currentImageIndex + 1);
        }
    } else {
        alert("已经是最后一张了");
    }
}

function prevImage() {
    if (currentImageIndex > 0) {
        loadImage(currentImageIndex - 1);
    }
}

// 快捷键
document.addEventListener('keydown', (e) => {
    // Ctrl+F: focus file filter
    if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault();
        const filter = document.getElementById('file-filter');
        if (filter) filter.focus();
        return;
    }

    // Ctrl+= / Ctrl+NumpadAdd: zoom in
    if ((e.ctrlKey || e.metaKey) && (e.key === '=' || e.key === '+' || e.code === 'NumpadAdd')) {
        e.preventDefault();
        zoomIn();
        return;
    }

    // Ctrl+- / Ctrl+NumpadSubtract: zoom out
    if ((e.ctrlKey || e.metaKey) && (e.key === '-' || e.code === 'NumpadSubtract')) {
        e.preventDefault();
        zoomOut();
        return;
    }

    // Ctrl+0: fit to screen
    if ((e.ctrlKey || e.metaKey) && e.key === '0') {
        e.preventDefault();
        zoomReset();
        return;
    }

    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') {
        // Allow navigation / editing keys to pass through even when input is focused
        const passThroughKeys = ['a', 'd', 'r', 'R', 's', 'Delete', 'Backspace', 'Escape', 'ArrowLeft', 'ArrowRight'];
        if (!passThroughKeys.includes(e.key)) {
            if (e.key === 'Enter') {
                if (document.getElementById('move-files-modal').style.display === 'block') {
                    executeMoveFiles();
                } else if (document.getElementById('edit-label-modal').style.display === 'block') {
                    confirmEditLabel();
                } else if (document.getElementById('label-modal').style.display === 'block') {
                    confirmLabel();
                }
            }
            return;
        }
    }

    if (e.key === 'r' || e.key === 'R') {
        isRectMode = !isRectMode; // Toggle mode
        updateModeUI();
    }

    if (e.key === 'Escape') {
        if (document.getElementById('move-files-modal').style.display === 'block') {
            closeMoveFilesModal();
        } else if (document.getElementById('edit-label-modal').style.display === 'block') {
            closeEditLabelModal();
        } else if (document.getElementById('label-modal').style.display === 'block') {
            closeModal();
        } else if (activeLabel) {
            // Deselect active label (X-AnyLabeling style)
            activeLabel = null;
            renderClassList();
            updateModeUI();
        } else {
            isDrawing = false;
            draw();
        }
    }

    if ((e.key === 'a' && !e.ctrlKey && !e.metaKey) || e.key === 'ArrowLeft') prevImage();
    if (e.key === 'd' || e.key === 'ArrowRight') nextImage();
    if (e.key === 's' && !e.ctrlKey && !e.metaKey) {
        toggleFileSelection(currentImageIndex);
        renderFileList();
    }

    if (e.key === 'Delete' && selectedShapeIndex !== -1) {
        deleteShape(selectedShapeIndex);
    }

    // Ctrl+A / Cmd+A: Select all visible files
    if ((e.ctrlKey || e.metaKey) && e.key === 'a') {
        // Only intercept when focus is not in an input/textarea
        if (document.activeElement && document.activeElement.tagName === 'INPUT' && document.activeElement.type === 'text') {
            return; // Let the default text selection work in filter input
        }
        e.preventDefault();
        selectAllFiles();
    }

    // Escape: Deselect all files
    if (e.key === 'Escape' && selectedFiles.size > 0) {
        deselectAllFiles();
    }
});

// Resize Observer to auto-fit on window resize
const resizeObserver = new ResizeObserver(() => {
    // Optional: Auto-refit on resize? Or keep scale?
    // Let's keep scale for now to avoid jumping, user can double click to reset.
});
resizeObserver.observe(document.getElementById('canvas-wrapper'));

// --- Auto Label Functions ---

async function openAutoLabelModal() {
    const modal = document.getElementById('auto-label-modal');
    modal.style.display = 'block';
    
    const container = document.getElementById('model-list-container');
    const hiddenInput = document.getElementById('selected-model-value');
    
    // Reset Progress UI
    resetProgressUI('auto-label');
    
    try {
        const res = await fetch('/api/models');
        const models = await res.json();
        
        container.innerHTML = '';
        if (models.length === 0) {
            container.innerHTML = '<div style="padding: 10px; color: #666; text-align: center;">No models found</div>';
            hiddenInput.value = '';
        } else {
            models.forEach((m, index) => {
                const itemDiv = document.createElement('div');
                itemDiv.style.display = 'flex';
                itemDiv.style.alignItems = 'center';
                itemDiv.style.width = '100%'; // Ensure full width
                itemDiv.style.boxSizing = 'border-box'; // Include padding in width
                itemDiv.style.padding = '8px 5px'; // Comfortable touch target
                itemDiv.style.borderBottom = '1px solid #eee';
                
                // Radio button for selection
                const radio = document.createElement('input');
                radio.type = 'radio';
                radio.name = 'model_choice';
                radio.value = m;
                radio.id = `model_radio_${index}`;
                radio.style.marginRight = '10px';
                radio.style.marginTop = '0'; 
                radio.style.flexShrink = '0'; // Never shrink
                // FIX: Force fixed size to override potential global "input { width: 100% }" styles
                radio.style.width = '20px'; 
                radio.style.height = '20px';
                radio.style.cursor = 'pointer';
                
                // Auto-select first model or keep previous selection if possible
                if (index === 0 && !hiddenInput.value) {
                    radio.checked = true;
                    hiddenInput.value = m;
                } else if (hiddenInput.value === m) {
                    radio.checked = true;
                }
                
                radio.onchange = () => { hiddenInput.value = m; };
                
                // Label text
                const label = document.createElement('label');
                label.htmlFor = `model_radio_${index}`;
                label.innerText = m;
                label.style.flex = '1 1 auto'; // Grow and shrink
                label.style.minWidth = '0'; // Allow shrinking below content size (magic fix for flex text)
                label.style.margin = '0';
                label.style.cursor = 'pointer';
                label.style.textAlign = 'left';
                label.style.wordBreak = 'break-all'; // Wrap long words so we can see full name
                label.style.lineHeight = '1.2';
                
                // Delete icon (X)
                const deleteBtn = document.createElement('span');
                deleteBtn.innerHTML = '&times;'; 
                deleteBtn.style.color = '#dc3545';
                deleteBtn.style.fontWeight = 'bold';
                deleteBtn.style.cursor = 'pointer';
                deleteBtn.style.fontSize = '20px';
                deleteBtn.style.lineHeight = '1';
                deleteBtn.style.padding = '0 8px';
                deleteBtn.style.marginLeft = '5px'; 
                deleteBtn.style.flexShrink = '0'; // Fixed size
                deleteBtn.title = 'Delete Model';
                
                deleteBtn.onclick = (e) => {
                    e.stopPropagation(); // Prevent triggering radio selection
                    deleteModelByName(m);
                };
                
                itemDiv.appendChild(radio);
                itemDiv.appendChild(label);
                itemDiv.appendChild(deleteBtn);
                container.appendChild(itemDiv);
            });
            
            // Ensure input has value if radios exist but none checked (edge case)
            const checked = container.querySelector('input[name="model_choice"]:checked');
            if (!checked && models.length > 0) {
                 const firstRadio = container.querySelector('input[name="model_choice"]');
                 if (firstRadio) {
                     firstRadio.checked = true;
                     hiddenInput.value = firstRadio.value;
                 }
            }
        }
    } catch (e) {
        container.innerHTML = '<div style="color: red;">Error loading models</div>';
    }
}

function closeAutoLabelModal() {
    document.getElementById('auto-label-modal').style.display = 'none';
}

async function uploadModelFile() {
    const fileInput = document.getElementById('model-upload');
    const renameInput = document.getElementById('model-rename-input');
    const file = fileInput.files[0];
    const statusDiv = document.getElementById('upload-status');
    
    if (!file) {
        statusDiv.innerText = 'Please select a file first.';
        statusDiv.style.color = 'red';
        return;
    }
    
    let customName = renameInput.value.trim();
    
    statusDiv.innerText = 'Uploading...';
    
    const formData = new FormData();
    formData.append('model_file', file);
    if (customName) {
        formData.append('custom_name', customName);
    }
    
    try {
        const res = await fetch('/api/upload_model', {
            method: 'POST',
            body: formData
        });
        const data = await res.json();
        
        if (data.status === 'success') {
            statusDiv.innerText = 'Upload successful!';
            statusDiv.style.color = 'green';
            fileInput.value = ''; // Clear file input
            renameInput.value = ''; // Clear rename input
            // Refresh model list
            openAutoLabelModal();
        } else {
            statusDiv.innerText = 'Error: ' + data.error;
            statusDiv.style.color = 'red';
        }
    } catch (e) {
        statusDiv.innerText = 'Upload failed';
        statusDiv.style.color = 'red';
    }
}

async function deleteModelByName(modelName) {
    if (!confirm(`Are you sure you want to delete model "${modelName}"?`)) {
        return;
    }
    
    try {
        const res = await fetch(`/api/delete_model/${encodeURIComponent(modelName)}`, {
            method: 'DELETE'
        });
        const data = await res.json();
        
        if (data.status === 'success') {
            // Refresh list
            openAutoLabelModal();
        } else {
            alert('Error deleting model: ' + (data.error || 'Unknown error'));
        }
    } catch (e) {
        alert('Error deleting model');
    }
}

async function runAutoLabel() {
    const hiddenInput = document.getElementById('selected-model-value');
    const modelName = hiddenInput.value;
    const conf = document.getElementById('conf-threshold').value;
    
    if (!modelName) {
        alert("Please select a model");
        return;
    }
    
    // UI Update
    const progressContainer = document.getElementById('auto-label-progress-container');
    const progressBar = document.getElementById('auto-label-progress-bar');
    const statusText = document.getElementById('auto-label-status-text');
    const countText = document.getElementById('auto-label-count');
    const runBtn = document.getElementById('auto-label-run-btn');
    const stopBtn = document.getElementById('auto-label-stop-btn');
    const cancelBtn = document.getElementById('auto-label-cancel-btn'); // Close button

    progressContainer.style.display = 'block';
    runBtn.disabled = true;
    runBtn.style.display = 'none';
    stopBtn.style.display = 'inline-block';
    
    statusText.innerText = "Starting...";
    progressBar.value = 0;
    
    try {
        const res = await fetch('/api/auto_label/' + PROJECT_KEY, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                model_name: modelName,
                conf: conf
            })
        });
        
        const data = await res.json();
        if (data.error) {
            alert("Error: " + data.error);
            resetProgressUI('auto-label');
            return;
        }
        
        currentTaskId = data.task_id;
        startPolling('auto-label');
        
    } catch (e) {
        alert("Request failed: " + e);
        resetProgressUI('auto-label');
    }
}

async function cancelAutoLabelTask() {
    if (!currentTaskId) return;
    
    try {
        await fetch('/api/cancel_task/' + currentTaskId, { method: 'POST' });
        document.getElementById('auto-label-status-text').innerText = "Cancelling...";
    } catch (e) {
        console.error("Cancel failed", e);
    }
}


// --- LLM Auto Label Functions ---

async function openLLMModal() {
    document.getElementById('llm-modal').style.display = 'block';
    resetProgressUI('llm');

    const useSamplesToggle = document.getElementById('llm-use-samples');
    const select = document.getElementById('llm-sample-project');

    if (useSamplesToggle && select) {
        useSamplesToggle.checked = true;
        select.disabled = false;
        select.style.opacity = '1';

        useSamplesToggle.onchange = () => {
            const enabled = useSamplesToggle.checked;
            select.disabled = !enabled;
            select.style.opacity = enabled ? '1' : '0.5';
        };
    }

    // Populate Sample Project Dropdown
    if (select) {
        // Keep default option
        select.innerHTML = '<option value="samples">Default (samples/)</option>';
        try {
            const res = await fetch('/api/projects');
            if (res.ok) {
                const projects = await res.json();
                projects.forEach(p => {
                    if (p === 'samples') return; // Skip default folder as it's already added
                    const option = document.createElement('option');
                    option.value = p;
                    option.innerText = p;
                    if (p === MAIN_FOLDER) {
                         option.disabled = true; // Avoid using self as sample (optional logic, but safer)
                         option.innerText += ' (Current)';
                    }
                    select.appendChild(option);
                });
            }
            
            // Auto-update prompt labels when sample source changes
            select.onchange = async function() {
                const val = select.value;
                const promptElem = document.getElementById('llm-prompt');
                if (!promptElem) return;

                try {
                    // Fetch labels from the selected sample project
                    const res = await fetch('/api/labels/' + encodeURIComponent(val));
                    if (res.ok) {
                        const labels = await res.json();
                        if (labels.length > 0) {
                             const currentPrompt = promptElem.value;
                             const regex = /(# Valid Labels List\s*)(\[[\s\S]*?\])/;
                             
                             if (regex.test(currentPrompt)) {
                                 const newLabelsStr = JSON.stringify(labels);
                                 promptElem.value = currentPrompt.replace(regex, '$1' + newLabelsStr);
                                 promptElem.style.borderColor = '#00ff00';
                                 setTimeout(() => promptElem.style.borderColor = '', 500);
                             }
                        }
                    }
                } catch (e) {
                    console.error("Error updating prompt labels", e);
                }
            };
        } catch (e) {
            console.error("Failed to load projects for sample selection", e);
        }
    }
}

function closeLLMModal() {
    document.getElementById('llm-modal').style.display = 'none';
}

async function runLLMAutoLabel() {
    const apiKey = document.getElementById('llm-api-key').value;
    const baseUrl = document.getElementById('llm-base-url').value;
    const model = document.getElementById('llm-model').value;
    const prompt = document.getElementById('llm-prompt').value;
    const sampleEnabled = document.getElementById('llm-use-samples').checked;
    const sampleProject = sampleEnabled ? document.getElementById('llm-sample-project').value : null;
    
    if (!apiKey || !baseUrl || !model) {
        alert("Please fill in all required fields");
        return;
    }
    
    // UI Update
    const progressContainer = document.getElementById('llm-progress-container');
    const progressBar = document.getElementById('llm-progress-bar');
    const statusText = document.getElementById('llm-status-text');
    const countText = document.getElementById('llm-count');
    const runBtn = document.getElementById('llm-run-btn');
    const stopBtn = document.getElementById('llm-stop-btn');
    
    progressContainer.style.display = 'block';
    runBtn.disabled = true;
    runBtn.style.display = 'none';
    stopBtn.style.display = 'inline-block';
    
    statusText.innerText = "Starting...";
    progressBar.value = 0;
    
    try {
        const res = await fetch('/api/auto_label_llm/' + PROJECT_KEY, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                api_key: apiKey,
                base_url: baseUrl,
                model: model,
                prompt: prompt,
                sample_project: sampleProject,
                sample_enabled: sampleEnabled
            })
        });
        
        const data = await res.json();
        if (data.error) {
            alert("Error: " + data.error);
            resetProgressUI('llm');
            return;
        }
        
        currentTaskId = data.task_id;
        startPolling('llm');
        
    } catch (e) {
        alert("Request failed: " + e);
        resetProgressUI('llm');
    }
}

async function cancelLLMTask() {
    if (!currentTaskId) return;
    try {
        await fetch('/api/cancel_task/' + currentTaskId, { method: 'POST' });
        document.getElementById('llm-status-text').innerText = "Cancelling...";
    } catch (e) {
        console.error("Cancel failed", e);
    }
}

// --- Common Task Polling ---

function startPolling(prefix) {
    if (taskPollInterval) clearInterval(taskPollInterval);
    
    taskPollInterval = setInterval(async () => {
        if (!currentTaskId) {
            clearInterval(taskPollInterval);
            return;
        }
        
        try {
            const res = await fetch('/api/task_status/' + currentTaskId);
            if (!res.ok) {
                // Task might be gone or error
                clearInterval(taskPollInterval);
                return;
            }
            
            const task = await res.json();
            
            // Update UI
            const progressBar = document.getElementById(prefix + '-progress-bar');
            const statusText = document.getElementById(prefix + '-status-text');
            const countText = document.getElementById(prefix + '-count');
            
            if (task.total > 0) {
                const pct = Math.round((task.progress / task.total) * 100);
                progressBar.value = pct;
                countText.innerText = `${task.progress}/${task.total}`;
            }
            
            if (task.status === 'completed') {
                clearInterval(taskPollInterval);
                statusText.innerText = "Completed!";
                statusText.style.color = "green";
                document.getElementById(prefix + '-stop-btn').style.display = 'none';
                document.getElementById(prefix + '-run-btn').style.display = 'inline-block';
                document.getElementById(prefix + '-run-btn').disabled = false;
                
                alert("Auto Labeling Completed! Processed: " + task.processed_count);
                location.reload(); // Reload to show new labels
            } else if (task.status === 'failed') {
                clearInterval(taskPollInterval);
                statusText.innerText = "Failed: " + (task.error || 'Unknown');
                statusText.style.color = "red";
                document.getElementById(prefix + '-stop-btn').style.display = 'none';
                document.getElementById(prefix + '-run-btn').style.display = 'inline-block';
                document.getElementById(prefix + '-run-btn').disabled = false;
            } else if (task.status === 'cancelled') {
                clearInterval(taskPollInterval);
                statusText.innerText = "Cancelled";
                statusText.style.color = "orange";
                document.getElementById(prefix + '-stop-btn').style.display = 'none';
                document.getElementById(prefix + '-run-btn').style.display = 'inline-block';
                document.getElementById(prefix + '-run-btn').disabled = false;
            }
            
        } catch (e) {
            console.error("Polling error", e);
        }
    }, 1000);
}

function resetProgressUI(prefix) {
    if (taskPollInterval) clearInterval(taskPollInterval);
    currentTaskId = null;
    
    const pb = document.getElementById(prefix + '-progress-bar');
    const st = document.getElementById(prefix + '-status-text');
    const ct = document.getElementById(prefix + '-count');
    const rb = document.getElementById(prefix + '-run-btn');
    const sb = document.getElementById(prefix + '-stop-btn');
    const pc = document.getElementById(prefix + '-progress-container');
    
    if (pb) pb.value = 0;
    if (st) st.innerText = "Ready";
    if (ct) ct.innerText = "0/0";
    if (rb) { rb.disabled = false; rb.style.display = 'inline-block'; }
    if (sb) sb.style.display = 'none';
    if (pc) pc.style.display = 'none';
}

// --- SAM3 Auto Label Functions ---

async function openAutoLabelSAM3Modal() {
    const modal = document.getElementById('auto-label-sam3-modal');
    modal.style.display = 'block';
    
    const container = document.getElementById('sam3-model-list-container');
    const hiddenInput = document.getElementById('selected-sam3-model-value');
    
    // Reset Progress UI
    resetProgressUI('sam3');
    
    // Populate Sample Project Dropdown (SAM3)
    const select = document.getElementById('sam3-sample-project');
    if (select) {
        select.innerHTML = '<option value="" disabled selected>Select Sample Source...</option>';
        try {
            const res = await fetch('/api/projects');
            if (res.ok) {
                const projects = await res.json();
                projects.forEach(p => {
                    const option = document.createElement('option');
                    option.value = p;
                    option.innerText = p;
                    if (p === MAIN_FOLDER) {
                         option.disabled = true;
                         option.innerText += ' (Current)';
                    }
                    select.appendChild(option);
                });
            }
        } catch (e) {
            console.error("Error loading projects for SAM3 samples", e);
        }
    }
    
    try {
        const res = await fetch('/api/models');
        const models = await res.json();
        
        container.innerHTML = '';
        if (models.length === 0) {
            container.innerHTML = '<div style="padding: 10px; color: #666; text-align: center;">No models found</div>';
            hiddenInput.value = '';
        } else {
            models.forEach((m, index) => {
                const itemDiv = document.createElement('div');
                itemDiv.style.display = 'flex';
                itemDiv.style.alignItems = 'center';
                itemDiv.style.width = '100%';
                itemDiv.style.boxSizing = 'border-box';
                itemDiv.style.padding = '8px 5px';
                itemDiv.style.borderBottom = '1px solid #eee';
                
                const radio = document.createElement('input');
                radio.type = 'radio';
                radio.name = 'sam3_model_choice';
                radio.value = m;
                radio.id = `sam3_model_radio_${index}`;
                radio.style.marginRight = '10px';
                radio.style.marginTop = '0'; 
                radio.style.flexShrink = '0';
                radio.style.width = '20px'; 
                radio.style.height = '20px';
                radio.style.cursor = 'pointer';
                
                if (index === 0 && !hiddenInput.value) {
                    radio.checked = true;
                    hiddenInput.value = m;
                } else if (hiddenInput.value === m) {
                    radio.checked = true;
                }
                
                radio.onchange = () => { hiddenInput.value = m; };
                
                const label = document.createElement('label');
                label.htmlFor = `sam3_model_radio_${index}`;
                label.innerText = m;
                label.style.flex = '1 1 auto';
                label.style.minWidth = '0';
                label.style.margin = '0';
                label.style.cursor = 'pointer';
                label.style.textAlign = 'left';
                label.style.wordBreak = 'break-all';
                label.style.lineHeight = '1.2';
                
                // Delete icon (X)
                const deleteBtn = document.createElement('span');
                deleteBtn.innerHTML = '&times;';
                deleteBtn.style.color = '#dc3545';
                deleteBtn.style.fontWeight = 'bold';
                deleteBtn.style.cursor = 'pointer';
                deleteBtn.style.fontSize = '20px';
                deleteBtn.style.lineHeight = '1';
                deleteBtn.style.padding = '0 8px';
                deleteBtn.style.marginLeft = '5px';
                deleteBtn.style.flexShrink = '0';
                deleteBtn.title = 'Delete Model';
                deleteBtn.onclick = (e) => {
                    e.stopPropagation();
                    deleteModelByNameSAM3(m);
                };
                
                itemDiv.appendChild(radio);
                itemDiv.appendChild(label);
                itemDiv.appendChild(deleteBtn);
                container.appendChild(itemDiv);
            });
            
            const checked = container.querySelector('input[name="sam3_model_choice"]:checked');
            if (!checked && models.length > 0) {
                 const firstRadio = container.querySelector('input[name="sam3_model_choice"]');
                 if (firstRadio) {
                     firstRadio.checked = true;
                     hiddenInput.value = firstRadio.value;
                 }
            }
        }
    } catch (e) {
        container.innerHTML = '<div style="color: red;">Error loading models</div>';
    }
}

function closeAutoLabelSAM3Modal() {
    document.getElementById('auto-label-sam3-modal').style.display = 'none';
}

async function deleteModelByNameSAM3(modelName) {
    if (!confirm(`确定要删除模型 "${modelName}" 吗？`)) {
        return;
    }
    try {
        const res = await fetch(`/api/delete_model/${encodeURIComponent(modelName)}`, {
            method: 'DELETE'
        });
        const data = await res.json();
        if (data.status === 'success') {
            // Refresh SAM3 list
            openAutoLabelSAM3Modal();
        } else {
            alert('删除模型失败: ' + (data.error || 'Unknown error'));
        }
    } catch (e) {
        alert('删除模型请求失败');
    }
}

async function runAutoLabelSAM3() {
    const hiddenInput = document.getElementById('selected-sam3-model-value');
    const modelName = hiddenInput.value;
    const conf = document.getElementById('sam3-conf-threshold').value;
    const sampleProjectName = document.getElementById('sam3-sample-project').value;
    
    if (!modelName) {
        alert("Please select a model");
        return;
    }

    if (!sampleProjectName) {
        alert("Please select a sample source");
        return;
    }
    
    const progressContainer = document.getElementById('sam3-progress-container');
    const progressBar = document.getElementById('sam3-progress-bar');
    const statusText = document.getElementById('sam3-status-text');
    const runBtn = document.getElementById('sam3-run-btn');
    const stopBtn = document.getElementById('sam3-stop-btn');
    
    progressContainer.style.display = 'block';
    runBtn.disabled = true;
    runBtn.style.display = 'none';
    stopBtn.style.display = 'inline-block';
    
    statusText.innerText = "Starting SAM3...";
    progressBar.value = 0;
    
    try {
        const res = await fetch('/api/auto_label_sam3/' + PROJECT_KEY, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                model_name: modelName,
                conf: conf,
                sample_project_name: sampleProjectName
            })
        });
        
        const data = await res.json();
        if (data.error) {
            alert("Error: " + data.error);
            resetProgressUI('sam3');
            return;
        }
        
        currentTaskId = data.task_id;
        startPolling('sam3');
        
    } catch (e) {
        alert("Request failed: " + e);
        resetProgressUI('sam3');
    }
}



window.onload = init;
