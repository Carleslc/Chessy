from .window import Window

from PyQt5.QtWidgets import QApplication

class App:

  def __init__(self, **kwargs):
    self.app = QApplication([])
    self.window = Window(**kwargs)
  
  def title(self, text):
    self.window.setWindowTitle(text)
  
  def start(self):
    self.window.show()
    self.app.exec()

  def display(self, svg):
    self.window.display(svg)

  def quit(self):
    self.app.quit()
