from PyQt5.QtWidgets import QHBoxLayout, QVBoxLayout, QWidget, QPushButton
from PyQt5.QtSvg import QSvgWidget

class Window(QWidget):

  def __init__(self, left = 100, top = 100, width = 500, height = 500):
    super().__init__()
    self.set_update_geometry(left, top, width, height)
    self.set_layout(QVBoxLayout())
  
  def set_update_geometry(self, left, top, width, height):
    self.left = max(0, left)
    self.top = max(0, top)
    self.width = max(20, width)
    self.height = max(20, height)
    self.update_geometry()
  
  def update_geometry(self):
    self.setGeometry(self.left, self.top, self.width, self.height)
  
  def set_layout(self, layout):
    self.layout = layout
    self.setLayout(layout)
  
  def display(self, svg):
    if not hasattr(self, 'widgetSvg'):
      self.widgetSvg = QSvgWidget()
      self.layout.addWidget(self.widgetSvg)
    
    self.widgetSvg.load(svg)
    self.repaint()

  def add_button(self, text, on_click, prepend = False):
    if not hasattr(self, 'btnLayout'):
      self.btnLayout = QHBoxLayout()
      self.layout.addLayout(self.btnLayout)
    
    btn = QPushButton(text)
    btn.clicked.connect(on_click)

    if prepend:
      self.btnLayout.insertWidget(0, btn)
    else:
      self.btnLayout.addWidget(btn)

    return btn

  def remove(self, widget):
    self.layout.removeWidget(widget)
    widget.deleteLater()
